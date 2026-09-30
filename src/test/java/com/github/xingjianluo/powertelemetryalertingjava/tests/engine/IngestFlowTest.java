package com.github.xingjianluo.powertelemetryalertingjava.tests.engine;

import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceState;
import com.github.xingjianluo.powertelemetryalertingjava.model.IngestBatchResult;
import com.github.xingjianluo.powertelemetryalertingjava.model.RawSample;
import com.github.xingjianluo.powertelemetryalertingjava.model.RecordStatus;
import com.github.xingjianluo.powertelemetryalertingjava.model.RejectReason;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 接入主流程：重复幂等、乱序归位、坏点不拖垮整批、同时间戳冲突。 */
class IngestFlowTest {

    private static final Logger log = LoggerFactory.getLogger(IngestFlowTest.class);

    private static final long T0 = 1_000_000_000_000L;
    private ManualClock clock;
    private ConfigProperties props;
    private InMemoryStateRepository repo;
    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
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
    @DisplayName("同一设备同一采样点重复上报：幂等，不重复累计")
    void duplicatesAreIdempotent() {
        List<RawSample> batch = List.of(
                raw(T0 + 1_000, 220, 5, 1000),
                raw(T0 + 2_000, 220, 5, 1100));
        IngestBatchResult first = registry.ingest("dev-dup", batch);
        assertEquals(2, first.accepted());

        // 同一批次内重复 + 冲突
        IngestBatchResult second = registry.ingest("dev-dup", List.of(
                raw(T0 + 1_000, 220, 5, 1000),   // 与已存完全相同 -> duplicate
                raw(T0 + 1_000, 220, 5, 1000),   // 对同批次已写入的点 -> duplicate
                raw(T0 + 2_000, 221, 5, 1100))); // 同时间戳不同读数 -> conflict
        assertEquals(0, second.accepted());
        assertEquals(2, second.duplicate());
        assertEquals(1, second.rejected());
        assertTrue(second.records().stream()
                .anyMatch(r -> r.status() == RecordStatus.REJECTED && r.reason() == RejectReason.CONFLICT));

        DeviceState state = registry.state("dev-dup").orElseThrow();
        assertEquals(2, state.getSamples().size());
        assertEquals(1, state.getWindows().size());
        WindowLike w = WindowLike.of(state);
        assertEquals(2, w.count());
        assertEquals(1050.0d, w.avg(), 1e-9);
        // 第三批完全重放：全部 duplicate，状态不变
        IngestBatchResult third = registry.ingest("dev-dup", batch);
        assertEquals(0, third.accepted());
        assertEquals(2, third.duplicate());
        DeviceState again = registry.state("dev-dup").orElseThrow();
        assertEquals(2, again.getSamples().size());
        log.info("duplicate handling: accepted={} duplicate={} rejected={} reasons={}",
                second.accepted(), second.duplicate(), second.rejected(),
                second.records().stream().map(r -> r.status() + ":" + r.reason()).toList());
    }

    @Test
    @DisplayName("乱序批次：迟到点按采样时间归位到正确窗口，聚合与顺序到达一致")
    void outOfOrderLandsInCorrectWindow() {
        // 墙钟推进到 70s：先发晚的点，再补 10s 的迟到点（reorderLag 10min 内）
        clock.setMillis(T0 + 70_000);
        registry.ingest("dev-ooo", List.of(raw(T0 + 70_000, 220, 5, 200)));
        IngestBatchResult late = registry.ingest("dev-ooo", List.of(raw(T0 + 10_000, 220, 5, 100)));
        assertEquals(1, late.accepted());

        DeviceState state = registry.state("dev-ooo").orElseThrow();
        // 两个点分别在 T0 窗口和 T0+60000 窗口
        assertEquals(2, state.getWindows().size());
        assertEquals(1, state.getWindows().get(WindowStart.of(T0 + 10_000, props)).getCount());
        assertEquals(1, state.getWindows().get(WindowStart.of(T0 + 70_000, props)).getCount());
        // 树内顺序正确
        assertEquals(T0 + 10_000, state.getSamples().firstKey());
        assertEquals(T0 + 70_000, state.getSamples().lastKey());
        log.info("out-of-order windows: starts={}", state.getWindows().keySet());
    }

    @Test
    @DisplayName("一个坏点不能拖垮整批：其余合法点照常入库，拒绝原因可区分")
    void oneBadPointDoesNotSinkBatch() {
        IngestBatchResult result = registry.ingest("dev-mix", List.of(
                raw(T0 + 1_000, 220, 5, 1000),
                raw(T0 + 2_000, Double.NaN, 5, 1000),          // 坏点
                raw(T0 + 3_000, 220, 5, 1200)));
        assertEquals(2, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals(RejectReason.INVALID_VALUE, result.records().stream()
                .filter(r -> r.status() == RecordStatus.REJECTED).findFirst().orElseThrow().reason());
        DeviceState state = registry.state("dev-mix").orElseThrow();
        assertEquals(2, state.getSamples().size());
        assertEquals(2, state.getWindows().firstEntry().getValue().getCount());
        log.info("mixed batch result: {}", result.records().stream()
                .map(r -> r.index() + ":" + r.status() + ":" + r.reason()).toList());
    }

    @Test
    @DisplayName("超出保留范围的迟到点被拒绝为 RETENTION_EXCEEDED，不影响后续写入")
    void beyondRetentionRejected() {
        // 用 3 个窗口的短保留 + 30min 回退容忍，隔离出 RETENTION_EXCEEDED 判定
        ConfigProperties shortRetention = new ConfigProperties();
        shortRetention.setRetainWindows(3);
        shortRetention.setReorderLagMs(1_800_000L);
        shortRetention.validate();
        DeviceRegistry reg = TestFactory.registry(shortRetention, new InMemoryStateRepository(), clock);

        // 数据先推进到 20 分钟（多个点，窗口前进）
        for (int m = 0; m <= 20; m++) {
            long ts = T0 + m * 60_000L;
            clock.setMillis(ts);
            assertEquals(1, reg.ingest("dev-ret", List.of(raw(ts, 220, 5, 1000))).accepted());
        }
        // 投一个 5 分钟前的点：相对最新点滞后 5min（< reorderLag），但其窗口已在 3 窗口保留之外
        IngestBatchResult old = reg.ingest("dev-ret", List.of(raw(T0 + 15 * 60_000L, 220, 5, 1000)));
        assertEquals(1, old.rejected());
        assertEquals(RejectReason.RETENTION_EXCEEDED, old.records().get(0).reason());
        // 新点仍可写
        clock.advance(1_000L);
        IngestBatchResult fresh = reg.ingest("dev-ret",
                List.of(raw(clock.millis(), 220, 5, 1000)));
        assertEquals(1, fresh.accepted());
    }

    @Test
    @DisplayName("非法设备 ID 被拒绝")
    void invalidDeviceIdRejected() {
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> registry.ingest("../etc", List.of(raw(T0, 220, 5, 1000))));
        assertTrue(ex.getMessage().contains("deviceId"));
        assertFalse(registry.deviceIds().contains("../etc"));
    }

    /** 简化断言辅助。 */
    private record WindowLike(int count, double avg) {
        static WindowLike of(DeviceState s) {
            var w = s.getWindows().firstEntry().getValue();
            return new WindowLike(w.getCount(), w.getAvgPowerW());
        }
    }

    private static class WindowStart {
        static long of(long ts, ConfigProperties props) {
            return Math.floorDiv(ts, props.getWindowMs()) * props.getWindowMs();
        }
    }
}
