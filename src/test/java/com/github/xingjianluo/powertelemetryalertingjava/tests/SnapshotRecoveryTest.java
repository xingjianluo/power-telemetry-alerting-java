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
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.Thresholds;
import com.github.xingjianluo.powertelemetryalertingjava.model.WindowStats;
import com.github.xingjianluo.powertelemetryalertingjava.persistence.SnapshotStore;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务重启恢复：采样、窗口统计、阈值、告警状态机与事件历史从 JSON 快照完整恢复，
 * 恢复后继续上报不重复累计、不乱触发告警。
 */
class SnapshotRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRecoveryTest.class);

    record Harness(AtomicLong clock, ServiceProperties props, DeviceRegistry registry,
                   SnapshotStore store, IngestService ingest) {
    }

    private Harness harness(Path snap) {
        return harness(snap, 3_000_000_000L);
    }

    private Harness harness(Path snap, long clockStart) {
        AtomicLong clock = new AtomicLong(clockStart);
        ServiceProperties props = new ServiceProperties();
        props.setSnapshotFile(snap.toString());
        props.setMaxHistoryMs(3600_000L);
        props.setFutureToleranceMs(60_000L);
        props.setAllowedLatenessMs(300_000L);
        props.setWindowMs(60_000L);
        props.setTriggerConsecutive(3);
        props.setRecoverConsecutive(3);
        props.setSnapshotAfterEachBatch(true);
        props.setDefaultOverVoltage(250d);
        props.setDefaultUnderVoltage(200d);
        props.setDefaultOverCurrent(30d);
        props.setDefaultOverPower(5000d);
        props.setDefaultMaxSampleGap(30_000L);
        DeviceRegistry registry = new DeviceRegistry();
        SnapshotStore store = new SnapshotStore(snap.toString());
        AlertEvaluator ev = new AlertEvaluator(clock::get, props);
        InterruptionMonitor im = new InterruptionMonitor(clock::get, ev, props);
        IngestService ingest = new IngestService(registry, new RecordValidator(clock::get), ev, im,
                new WindowManager(), store, props, clock::get);
        return new Harness(clock, props, registry, store, ingest);
    }

    private TelemetryPointDto p(AtomicLong clock, int second, double v, double c, double w) {
        return new TelemetryPointDto(clock.get() + second * 1000L, v, c, w);
    }

    @Test
    void stateSurvivesRestartAndProcessingContinuesConsistently() throws Exception {
        Path snap = Files.createTempDirectory("snap3").resolve("snapshot.json");

        // ---- 第一个服务实例：产生窗口统计、触发一条过压告警、设置自定义阈值 ----
        Harness h1 = harness(snap);
        h1.ingest().ingest("dev-R", List.of(
                p(h1.clock(), 0, 255d, 10d, 2200d),
                p(h1.clock(), 1, 256d, 10d, 2300d),
                p(h1.clock(), 2, 257d, 10d, 2400d)));
        var cfgSvc = new com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceConfigService(
                h1.registry(), h1.store(), h1.props(), h1.clock()::get);
        cfgSvc.updateThresholds("dev-R",
                new Thresholds(260d, 190d, null, 6000d, 45_000L));

        DeviceState before = h1.registry().get("dev-R");
        List<AlertEvent> eventsBefore;
        WindowStats windowBefore;
        before.getLock().lock();
        try {
            eventsBefore = List.copyOf(before.getAlertEvents());
            windowBefore = before.getWindows().firstEntry().getValue().toStats();
        } finally {
            before.getLock().unlock();
        }
        assertTrue(Files.exists(snap), "批次后快照文件应存在");
        log.info("重启前：事件={} 窗口点数={} 平均功率={}W 峰值={}W 能耗={}Wh",
                eventsBefore.stream().map(e -> e.type() + ":" + e.state()).toList(),
                windowBefore.sampleCount(), windowBefore.avgPower(), windowBefore.peakPower(),
                windowBefore.energyWh());

        // ---- 模拟服务重启：新注册表 + 加载快照（时钟沿用重启前时刻）----
        Harness h2 = harness(snap, h1.clock().get());
        h2.store().loadInto(h2.registry());
        DeviceState after = h2.registry().get("dev-R");
        assertNotNull(after, "设备应从快照恢复");
        after.getLock().lock();
        try {
            assertEquals(3, after.getSamples().size(), "采样点完整恢复");
            assertEquals(eventsBefore.size(), after.getAlertEvents().size(), "告警事件历史恢复");
            DeviceConfig cfg = after.getConfig();
            assertEquals(260d, cfg.thresholds().overVoltage(), 1e-9, "自定义阈值恢复");
            assertEquals(45_000L, cfg.thresholds().maxSampleGap(), "自定义中断间隔恢复");
            WindowStats restoredWindow = after.getWindows().firstEntry().getValue().toStats();
            assertEquals(windowBefore.avgPower(), restoredWindow.avgPower(), 1e-9, "平均功率恢复一致");
            assertEquals(windowBefore.peakPower(), restoredWindow.peakPower(), 1e-9, "峰值功率恢复一致");
            assertEquals(windowBefore.energyWh(), restoredWindow.energyWh(), 1e-12, "能耗恢复一致");
            // 过压仍处于 ACTIVE 状态（状态机恢复）
            assertTrue(after.getAlertStatuses().get(AlertType.OVER_VOLTAGE).isActive(),
                    "ACTIVE 告警状态机应恢复");
        } finally {
            after.getLock().unlock();
        }
        log.info("重启恢复完成 devices={} 事件数={} 自定义阈值生效", h2.registry().size(),
                eventsBefore.size());

        // ---- 恢复后继续上报：旧点重复拒绝，阈值已放宽到 260，257V 不再越限 ----
        long historicalTs = 3_000_000_000L + 2_000; // 与重启前第3个采样点完全相同
        var dup = h2.ingest().ingest("dev-R", List.of(
                new TelemetryPointDto(historicalTs, 257d, 10d, 2400d)));
        assertEquals(0, dup.accepted());
        assertEquals(1, dup.duplicate(), "重启后重复点仍不能重复累计");

        h2.ingest().ingest("dev-R", List.of(
                p(h2.clock(), 6, 257d, 10d, 2500d),
                p(h2.clock(), 7, 258d, 10d, 2600d),
                p(h2.clock(), 8, 259d, 10d, 2700d)));
        DeviceState continued = h2.registry().get("dev-R");
        continued.getLock().lock();
        try {
            assertEquals(6, continued.getSamples().size(), "恢复后新点正常接入，总计 6 点");
            long raises = continued.getAlertEvents().stream()
                    .filter(e -> e.type() == AlertType.OVER_VOLTAGE && e.state() == AlertState.ACTIVE)
                    .count();
            long clears = continued.getAlertEvents().stream()
                    .filter(e -> e.type() == AlertType.OVER_VOLTAGE && e.state() == AlertState.RECOVERED)
                    .count();
            assertEquals(1, raises, "重启后没有第二次触发（259 < 新阈值260）");
            assertEquals(1, clears, "连续3点低于新阈值后恰好恢复一次");
            log.info("恢复后继续接入：总点数=6 过压保持1次触发并按新阈值恢复1次，结论可预测");
        } finally {
            continued.getLock().unlock();
        }
    }
}
