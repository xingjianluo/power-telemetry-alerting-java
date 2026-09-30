package com.github.xingjianluo.powertelemetryalertingjava.tests;

import com.github.xingjianluo.powertelemetryalertingjava.api.dto.TelemetryPointDto;
import com.github.xingjianluo.powertelemetryalertingjava.config.ServiceProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.AlertEvaluator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.IngestService;
import com.github.xingjianluo.powertelemetryalertingjava.engine.InterruptionMonitor;
import com.github.xingjianluo.powertelemetryalertingjava.engine.RecordValidator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.WindowManager;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertEvent;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertState;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertType;
import com.github.xingjianluo.powertelemetryalertingjava.persistence.SnapshotStore;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阈值抖动场景：连续计数去抖，短暂越限/恢复不导致告警横跳；稳定恢复才解除。
 */
class AlertDebounceTest {

    private static final Logger log = LoggerFactory.getLogger(AlertDebounceTest.class);

    private AtomicLong clock;
    private IngestService ingest;
    private DeviceRegistry registry;
    private ServiceProperties props;
    private Path snapshotPath;

    @BeforeEach
    void setUp() throws Exception {
        clock = new AtomicLong(2_000_000_000L);
        Path snap = Files.createTempDirectory("snap2").resolve("s.json");
        props = new ServiceProperties();
        snapshotPath = snap;
        props.setSnapshotFile(snap.toString());
        props.setMaxHistoryMs(3600_000L);
        props.setFutureToleranceMs(60_000L);
        props.setAllowedLatenessMs(300_000L);
        props.setWindowMs(60_000L);
        props.setTriggerConsecutive(3);
        props.setRecoverConsecutive(3);
        props.setSnapshotAfterEachBatch(false);
        props.setDefaultOverVoltage(250d);
        props.setDefaultUnderVoltage(200d);
        props.setDefaultOverCurrent(30d);
        props.setDefaultOverPower(5000d);
        props.setDefaultMaxSampleGap(30_000L);
        registry = new DeviceRegistry();
        SnapshotStore store = new SnapshotStore(snap.toString());
        AlertEvaluator ev = new AlertEvaluator(clock::get, props);
        InterruptionMonitor im = new InterruptionMonitor(clock::get, ev, props);
        ingest = new IngestService(registry, new RecordValidator(clock::get), ev, im,
                new WindowManager(), store, props, clock::get);
    }

    private TelemetryPointDto p(int second, double v, double c, double w) {
        return new TelemetryPointDto(clock.get() + second * 1000L, v, c, w);
    }

    private List<AlertEvent> events(String device) {
        return registry.get(device).getAlertEvents();
    }

    private long count(String device, AlertType type, AlertState state) {
        return events(device).stream()
                .filter(e -> e.type() == type && e.state() == state).count();
    }

    @Test
    void briefSpikeDoesNotRaiseAndFlickerDoesNotClear() {
        // 2 个越限点后立即恢复 → 不触发
        ingest.ingest("dev-1", List.of(
                p(0, 251d, 10d, 1000d), p(1, 252d, 10d, 1000d), p(2, 220d, 10d, 1000d)));
        assertEquals(0, count("dev-1", AlertType.OVER_VOLTAGE, AlertState.ACTIVE));
        log.info("2连越限后恢复，未触发过压（抖动被抑制）");

        // 再连续 3 个越限 → 触发一次
        ingest.ingest("dev-1", List.of(
                p(3, 255d, 10d, 1000d), p(4, 256d, 10d, 1000d), p(5, 257d, 10d, 1000d)));
        assertEquals(1, count("dev-1", AlertType.OVER_VOLTAGE, AlertState.ACTIVE));

        // 告警中插入 2 个正常点再越限 → 不解除（恢复计数不足 3 且被重置）
        ingest.ingest("dev-1", List.of(
                p(6, 220d, 10d, 1000d), p(7, 220d, 10d, 1000d), p(8, 260d, 10d, 1000d)));
        assertEquals(0, count("dev-1", AlertType.OVER_VOLTAGE, AlertState.RECOVERED),
                "恢复点数不足时不能解除告警");
        assertEquals(1, count("dev-1", AlertType.OVER_VOLTAGE, AlertState.ACTIVE),
                "不能重复触发告警");
        log.info("告警中2连恢复后再越限，未横跳：仍为1次触发、0次恢复");

        // 连续 3 个正常点 → 恰好解除一次
        ingest.ingest("dev-1", List.of(
                p(9, 220d, 10d, 1000d), p(10, 220d, 10d, 1000d), p(11, 220d, 10d, 1000d)));
        assertEquals(1, count("dev-1", AlertType.OVER_VOLTAGE, AlertState.RECOVERED));
        log.info("连续3点恢复后解除过压，事件序列: {}",
                events("dev-1").stream().map(e -> e.state() + "@" + e.sampleTime()).toList());
    }

    @Test
    void independentThresholdTypesDoNotInterfere() {
        // 同时过压+过流3连，应分别触发；恢复也分别计数
        ingest.ingest("dev-2", List.of(
                p(0, 260d, 31d, 6000d), p(1, 260d, 31d, 6000d), p(2, 260d, 31d, 6000d)));
        assertEquals(1, count("dev-2", AlertType.OVER_VOLTAGE, AlertState.ACTIVE));
        assertEquals(1, count("dev-2", AlertType.OVER_CURRENT, AlertState.ACTIVE));
        assertEquals(1, count("dev-2", AlertType.OVER_POWER, AlertState.ACTIVE));
        // 只让电压恢复，电流/功率仍越限
        ingest.ingest("dev-2", List.of(
                p(3, 220d, 31d, 6000d), p(4, 220d, 31d, 6000d), p(5, 220d, 31d, 6000d)));
        assertEquals(1, count("dev-2", AlertType.OVER_VOLTAGE, AlertState.RECOVERED));
        assertEquals(0, count("dev-2", AlertType.OVER_CURRENT, AlertState.RECOVERED));
        assertEquals(0, count("dev-2", AlertType.OVER_POWER, AlertState.RECOVERED));
        DeviceState s = registry.get("dev-2");
        assertTrue(s.getAlertStatuses().get(AlertType.OVER_VOLTAGE) != null
                && !s.getAlertStatuses().get(AlertType.OVER_VOLTAGE).isActive());
        log.info("多阈值独立判定：过压已恢复，过流/过功率仍 ACTIVE");
    }

    @Test
    void disablingAlertsStopsNewEventsButKeepsHistoryAndData() {
        ingest.ingest("dev-3", List.of(
                p(0, 260d, 10d, 1000d), p(1, 260d, 10d, 1000d), p(2, 260d, 10d, 1000d)));
        assertEquals(1, count("dev-3", AlertType.OVER_VOLTAGE, AlertState.ACTIVE));

        com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceConfigService svc =
                new com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceConfigService(
                        registry, new SnapshotStore(snapshotPath.toString()), props, clock::get);
        svc.setAlertsEnabled("dev-3", false);

        // 继续严重越限，不应再产生任何事件；采样照常接入
        ingest.ingest("dev-3", List.of(
                p(3, 270d, 10d, 9000d), p(4, 270d, 10d, 9000d), p(5, 270d, 10d, 9000d)));
        assertEquals(1, events("dev-3").size(), "停用后历史事件保留且无新事件");
        assertEquals(6, registry.get("dev-3").getSamples().size(), "停用告警不影响数据接入");
        assertFalse(registry.get("dev-3").getConfig().alertsEnabled());
        log.info("告警停用后：采样继续接入(6点)，历史事件保留(1条)，无新告警");
    }
}
