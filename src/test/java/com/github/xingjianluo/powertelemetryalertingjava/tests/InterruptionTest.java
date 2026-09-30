package com.github.xingjianluo.powertelemetryalertingjava.tests;

import com.github.xingjianluo.powertelemetryalertingjava.api.dto.TelemetryPointDto;
import com.github.xingjianluo.powertelemetryalertingjava.config.ServiceProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.AlertEvaluator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.IngestService;
import com.github.xingjianluo.powertelemetryalertingjava.engine.InterruptionMonitor;
import com.github.xingjianluo.powertelemetryalertingjava.engine.InterruptionSweepScheduler;
import com.github.xingjianluo.powertelemetryalertingjava.engine.RecordValidator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.WindowManager;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采样中断：相邻点间隔超限触发；设备彻底停发时墙钟扫描兜底触发；
 * 两种路径共用状态机不重复触发；恢复采样后恰好恢复一次。
 */
class InterruptionTest {

    private static final Logger log = LoggerFactory.getLogger(InterruptionTest.class);

    private AtomicLong clock;
    private ServiceProperties props;
    private DeviceRegistry registry;
    private IngestService ingest;
    private InterruptionSweepScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        clock = new AtomicLong(4_000_000_000L);
        Path snap = Files.createTempDirectory("snap4").resolve("s.json");
        props = new ServiceProperties();
        props.setSnapshotFile(snap.toString());
        props.setMaxHistoryMs(3600_000L);
        props.setFutureToleranceMs(60_000L);
        props.setAllowedLatenessMs(300_000L);
        props.setWindowMs(60_000L);
        props.setTriggerConsecutive(3);
        props.setRecoverConsecutive(3);
        props.setSnapshotAfterEachBatch(false);
        props.setDefaultOverVoltage(999d);
        props.setDefaultUnderVoltage(0d);
        props.setDefaultOverCurrent(999d);
        props.setDefaultOverPower(999_999d);
        props.setDefaultMaxSampleGap(30_000L);
        registry = new DeviceRegistry();
        SnapshotStore store = new SnapshotStore(snap.toString());
        AlertEvaluator ev = new AlertEvaluator(clock::get, props);
        InterruptionMonitor im = new InterruptionMonitor(clock::get, ev, props);
        ingest = new IngestService(registry, new RecordValidator(clock::get), ev, im,
                new WindowManager(), store, props, clock::get);
        scheduler = new InterruptionSweepScheduler(registry, im, props, store, clock::get);
    }

    private TelemetryPointDto p(long ts) {
        return new TelemetryPointDto(ts, 220d, 10d, 2000d);
    }

    private long interruptCount(AlertState state) {
        DeviceState s = registry.get("dev-I");
        return s.getAlertEvents().stream()
                .filter(e -> e.type() == AlertType.SAMPLE_INTERRUPT && e.state() == state).count();
    }

    @Test
    void gapOnArrivalRaisesAndResumptionClearsExactlyOnce() {
        long t0 = clock.get();
        ingest.ingest("dev-I", List.of(p(t0), p(t0 + 5_000)));
        assertEquals(0, interruptCount(AlertState.ACTIVE), "5s 间隔不中断（阈值30s）");

        // 间隔 40s 的新点到达 → 触发
        var r = ingest.ingest("dev-I", List.of(p(t0 + 45_000)));
        assertEquals(1, r.alertsRaised().size());
        assertEquals(AlertType.SAMPLE_INTERRUPT, r.alertsRaised().get(0).type());
        assertEquals(1, interruptCount(AlertState.ACTIVE));
        log.info("相邻点间隔45s>30s，触发采样中断: {}", r.alertsRaised().get(0).detail());

        // 中断后来点 → 恢复；再来点不重复触发也不重复恢复
        ingest.ingest("dev-I", List.of(p(t0 + 50_000)));
        ingest.ingest("dev-I", List.of(p(t0 + 55_000)));
        assertEquals(1, interruptCount(AlertState.RECOVERED));
        assertEquals(1, interruptCount(AlertState.ACTIVE));
        log.info("恢复采样后恰好恢复1次，后续正常点不再产生中断事件");
    }

    @Test
    void wallClockSweepCatchesSilentDeviceAndDoesNotDoubleRaise() {
        long t0 = clock.get();
        ingest.ingest("dev-I", List.of(p(t0)));
        // 设备彻底不发数据：墙钟推进 31s 后扫描触发
        clock.addAndGet(31_000);
        scheduler.sweepOnce();
        assertEquals(1, interruptCount(AlertState.ACTIVE), "墙钟扫描应触发中断");

        // 再扫多次不重复触发
        scheduler.sweepOnce();
        scheduler.sweepOnce();
        assertEquals(1, interruptCount(AlertState.ACTIVE));

        // 迟到一个"间隔内"的点不会发生；设备恢复发点即恢复
        var r = ingest.ingest("dev-I", List.of(p(clock.get())));
        assertEquals(1, r.alertsCleared().size());
        assertEquals(1, interruptCount(AlertState.RECOVERED));
        assertTrue(registry.get("dev-I").getAlertEvents().get(0).detail().contains("墙钟扫描"),
                "触发依据需可追溯到墙钟扫描");
        log.info("墙钟扫描触发不重复，恢复采样后恢复；事件依据={}",
                registry.get("dev-I").getAlertEvents().stream().map(Object::toString).toList());
    }
}
