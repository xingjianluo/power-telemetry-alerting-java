package com.github.xingjianluo.powertelemetryalertingjava.tests.engine;

import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.EffectiveConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.RawSample;
import com.github.xingjianluo.powertelemetryalertingjava.tests.support.InMemoryStateRepository;
import com.github.xingjianluo.powertelemetryalertingjava.tests.support.ManualClock;
import com.github.xingjianluo.powertelemetryalertingjava.tests.support.TestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 设备级配置：PATCH 合并、PUT 全量替换、非法拒绝且不动状态、停用告警立即恢复活动告警。 */
class DeviceConfigTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceConfigTest.class);

    private static final long T0 = 1_000_000_000_000L;
    private ManualClock clock;
    private ConfigProperties props;
    private InMemoryStateRepository repo;
    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0 + 10_000);
        props = new ConfigProperties();
        props.validate();
        repo = new InMemoryStateRepository();
        registry = TestFactory.registry(props, repo, clock);
    }

    private RawSample raw(long ts, double v, double a, double w) {
        RawSample r = new RawSample();
        r.setTimestampMs(ts);
        r.setVoltageV(v);
        r.setCurrentA(a);
        r.setPowerW(w);
        return r;
    }

    @Test
    @DisplayName("PATCH 逐字段合并：未提供的字段保持原值或默认")
    void patchMergesFields() {
        DeviceConfig first = new DeviceConfig();
        first.setOverVoltageV(260.0);
        first.setBreachTimes(5);
        registry.updateConfig("dev-cfg", first, false);

        DeviceConfig second = new DeviceConfig();
        second.setOverCurrentA(20.0);
        registry.updateConfig("dev-cfg", second, false);

        DeviceConfig stored = registry.getConfigOverride("dev-cfg");
        assertEquals(260.0, stored.getOverVoltageV(), 0.0d);
        assertEquals(5, stored.getBreachTimes());
        assertEquals(20.0, stored.getOverCurrentA(), 0.0d);
        assertNull(stored.getUnderVoltageV());

        EffectiveConfig effective = EffectiveConfig.merge(props, stored);
        assertEquals(260.0, effective.overVoltageV(), 0.0d);
        assertEquals(20.0, effective.overCurrentA(), 0.0d);
        assertEquals(props.getDefaultUnderVoltageV(), effective.underVoltageV(), 0.0d);
        log.info("merged effective config: overV={} underV={} overA={} breach={}",
                effective.overVoltageV(), effective.underVoltageV(), effective.overCurrentA(),
                effective.breachTimes());
    }

    @Test
    @DisplayName("PUT 全量替换：未提供的字段回到默认")
    void putReplacesWholeConfig() {
        DeviceConfig first = new DeviceConfig();
        first.setOverVoltageV(260.0);
        first.setBreachTimes(5);
        registry.updateConfig("dev-cfg2", first, false);

        DeviceConfig replacement = new DeviceConfig();
        replacement.setOverPowerW(5000.0);
        registry.updateConfig("dev-cfg2", replacement, true);

        DeviceConfig stored = registry.getConfigOverride("dev-cfg2");
        assertEquals(5000.0, stored.getOverPowerW(), 0.0d);
        assertNull(stored.getOverVoltageV());
        assertNull(stored.getBreachTimes());
        EffectiveConfig effective = EffectiveConfig.merge(props, stored);
        assertEquals(props.getDefaultOverVoltageV(), effective.overVoltageV(), 0.0d);
        assertEquals(props.getDefaultBreachTimes(), effective.breachTimes());
    }

    @Test
    @DisplayName("非法配置（阈值倒挂/非正/计数<1）被拒绝，已有状态与配置不受影响")
    void invalidConfigRejectedAtomically() {
        DeviceConfig valid = new DeviceConfig();
        valid.setOverVoltageV(260.0);
        registry.updateConfig("dev-inv", valid, false);
        // 先写入一点数据和活跃告警
        for (int i = 0; i < 3; i++) {
            registry.ingest("dev-inv", List.of(raw(T0 + i * 1_000L, 261, 5, 1000)));
        }
        long revision = registry.state("dev-inv").orElseThrow().getRevision();

        assertInvalid(newOverUnder(100.0, 200.0), "over voltage below under voltage rejected");
        assertInvalid(positive(0.0), "zero threshold rejected");
        assertInvalid(negative(-1.0), "negative threshold rejected");
        assertInvalid(nan(), "NaN threshold rejected");
        assertInvalid(breach(0), "breachTimes 0 rejected");
        assertInvalid(timeout(0L), "zero timeout rejected");

        DeviceConfig still = registry.getConfigOverride("dev-inv");
        assertEquals(260.0, still.getOverVoltageV(), 0.0d, "valid config untouched");
        assertEquals(revision, registry.state("dev-inv").orElseThrow().getRevision(),
                "state untouched by rejected config change");
    }

    private void assertInvalid(DeviceConfig cfg, String label) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.updateConfig("dev-inv", cfg, false), label);
        log.info("invalid config rejected ({}): {}", label, ex.getMessage());
    }

    private DeviceConfig newOverUnder(double over, double under) {
        DeviceConfig c = new DeviceConfig();
        c.setOverVoltageV(over);
        c.setUnderVoltageV(under);
        return c;
    }

    private DeviceConfig positive(double v) {
        DeviceConfig c = new DeviceConfig();
        c.setOverPowerW(v);
        return c;
    }

    private DeviceConfig negative(double v) {
        return positive(v);
    }

    private DeviceConfig nan() {
        return positive(Double.NaN);
    }

    private DeviceConfig breach(int n) {
        DeviceConfig c = new DeviceConfig();
        c.setBreachTimes(n);
        return c;
    }

    private DeviceConfig timeout(long ms) {
        DeviceConfig c = new DeviceConfig();
        c.setInterruptionTimeoutMs(ms);
        return c;
    }

    @Test
    @DisplayName("停用告警时活动告警立即恢复，且停用期间不产生新告警；重新启用后按新数据判定")
    void disableEnablesAlerts() {
        // 3 次过压 -> 活跃
        for (int i = 0; i < 3; i++) {
            registry.ingest("dev-toggle", List.of(raw(T0 + i * 1_000L, 261, 5, 1000)));
        }
        assertTrue(registry.state("dev-toggle").orElseThrow()
                .runtime(com.github.xingjianluo.powertelemetryalertingjava.model.AlertType.OVER_VOLTAGE)
                .isActive());

        DeviceConfig off = new DeviceConfig();
        off.setAlertsEnabled(false);
        registry.updateConfig("dev-toggle", off, false);
        assertFalse(registry.state("dev-toggle").orElseThrow()
                .runtime(com.github.xingjianluo.powertelemetryalertingjava.model.AlertType.OVER_VOLTAGE)
                .isActive(), "active alerts force-recovered on disable");

        // 停用期间持续过压也不触发
        var silent = registry.ingest("dev-toggle", List.of(raw(T0 + 3_000L, 261, 5, 1000)));
        assertEquals(0, silent.alerts().size());

        // 重新启用：从水位线之后的新数据重新开始计数，历史不翻旧账
        DeviceConfig on = new DeviceConfig();
        on.setAlertsEnabled(true);
        registry.updateConfig("dev-toggle", on, false);
        var one = registry.ingest("dev-toggle", List.of(raw(T0 + 4_000L, 261, 5, 1000)));
        assertEquals(0, one.alerts().size(), "first breach after re-enable must not immediately trigger");
    }
}
