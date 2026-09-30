package com.github.xingjianluo.powertelemetryalertingjava.tests;

import com.github.xingjianluo.powertelemetryalertingjava.api.dto.TelemetryPointDto;
import com.github.xingjianluo.powertelemetryalertingjava.config.ServiceProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.AlertEvaluator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.IngestService;
import com.github.xingjianluo.powertelemetryalertingjava.engine.InterruptionMonitor;
import com.github.xingjianluo.powertelemetryalertingjava.engine.RecordValidator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.WindowManager;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertState;
import com.github.xingjianluo.powertelemetryalertingjava.model.IngestResult;
import com.github.xingjianluo.powertelemetryalertingjava.model.RejectCode;
import com.github.xingjianluo.powertelemetryalertingjava.persistence.SnapshotStore;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
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
 * 接入层：坏点隔离、拒绝原因可区分、重复不累计、乱序归位、确定性重放。
 */
class IngestCoreTest {

    private static final Logger log = LoggerFactory.getLogger(IngestCoreTest.class);

    private AtomicLong clock;
    private IngestService ingest;
    private DeviceRegistry registry;
    private Path snapshotFile;

    private TelemetryPointDto p(Long ts, Double v, Double c, Double w) {
        return new TelemetryPointDto(ts, v, c, w);
    }

    @BeforeEach
    void setUp() throws Exception {
        clock = new AtomicLong(1_000_000_000L);
        snapshotFile = Files.createTempDirectory("snap").resolve("snapshot.json");
        ServiceProperties props = new ServiceProperties();
        props.setSnapshotFile(snapshotFile.toString());
        props.setMaxHistoryMs(3600_000L);
        props.setFutureToleranceMs(2_000L);
        props.setAllowedLatenessMs(30_000L);
        props.setWindowMs(60_000L);
        props.setTriggerConsecutive(3);
        props.setRecoverConsecutive(2);
        props.setSnapshotAfterEachBatch(false);
        props.setDefaultOverVoltage(250d);
        props.setDefaultUnderVoltage(200d);
        props.setDefaultOverCurrent(30d);
        props.setDefaultOverPower(5000d);
        props.setDefaultMaxSampleGap(30_000L);

        registry = new DeviceRegistry();
        SnapshotStore store = new SnapshotStore(snapshotFile.toString());
        RecordValidator validator = new RecordValidator(clock::get);
        AlertEvaluator evaluator = new AlertEvaluator(clock::get, props);
        InterruptionMonitor interruption = new InterruptionMonitor(clock::get, evaluator, props);
        ingest = new IngestService(registry, validator, evaluator, interruption,
                new WindowManager(), store, props, clock::get);
    }

    @Test
    void badPointsAreIsolatedWithDistinctReasons() {
        long now = clock.get();
        List<TelemetryPointDto> batch = List.of(
                p(now, 220d, 10d, 2200d),            // 0 正常
                p(now, null, 10d, 2200d),           // 1 缺电压
                p(now + 1, 220d, 10d, null),        // 2 缺功率
                p(now + 2, Double.NaN, 10d, 1d),    // 3 数值非法
                p(now + 100_000, 220d, 10d, 1d),    // 4 未来时间
                p(now - 10_000_000, 220d, 10d, 1d), // 5 超出历史范围
                p(null, 220d, 10d, 2200d)           // 6 缺时间戳
        );
        IngestResult r = ingest.ingest("dev-A", batch);
        assertEquals(1, r.accepted(), "只有一条好点被接受");
        assertEquals(6, r.rejected().size(), "坏点逐条拒绝，不拖垮整批");
        assertEquals(RejectCode.MISSING_FIELD, r.rejected().get(0).code());
        assertEquals(RejectCode.MISSING_FIELD, r.rejected().get(1).code());
        assertEquals(RejectCode.INVALID_VALUE, r.rejected().get(2).code());
        assertEquals(RejectCode.FUTURE_TIMESTAMP, r.rejected().get(3).code());
        assertEquals(RejectCode.OUT_OF_RANGE, r.rejected().get(4).code());
        assertEquals(RejectCode.MISSING_FIELD, r.rejected().get(5).code());
        r.rejected().forEach(x -> log.info("拒绝[{}] device={} sampleTime={} code={} reason={}",
                x.index(), x.deviceId(), x.sampleTime(), x.code(), x.reason()));
        assertTrue(r.rejected().stream().allMatch(x -> x.reason() != null && !x.reason().isBlank()));
    }

    @Test
    void duplicatesAreNotAccumulatedAcrossAndWithinBatches() {
        long now = clock.get();
        IngestResult first = ingest.ingest("dev-B", List.of(
                p(now, 220d, 10d, 1000d),
                p(now + 1_000, 220d, 10d, 1100d)));
        assertEquals(2, first.accepted());

        // 跨批次重复 + 批内重复
        IngestResult second = ingest.ingest("dev-B", List.of(
                p(now, 220d, 10d, 9999d),           // 跨批重复
                p(now + 1_000, 220d, 10d, 1100d),   // 跨批重复
                p(now + 2_000, 220d, 10d, 1200d),   // 新点
                p(now + 2_000, 220d, 10d, 1200d))); // 批内重复
        assertEquals(1, second.accepted());
        assertEquals(3, second.duplicate());
        assertEquals(3, second.rejected().size());
        assertTrue(second.rejected().stream().allMatch(x -> x.code() == RejectCode.DUPLICATE));

        // 重复点的功率没有被累计：窗口峰值仍为 1200 而非 9999
        double peak = registry.get("dev-B").getWindows().firstEntry().getValue().toStats().peakPower();
        assertEquals(1200d, peak, 1e-9);
        assertEquals(3, registry.get("dev-B").getSamples().size(), "采样点总数不重复累计");
        log.info("重复校验通过 device=dev-B 唯一点数=3 窗口峰值={}W（重复点 9999W 未累计）", peak);
    }

    @Test
    void outOfOrderPointsAreReplayedAndConclusionIsReproducible() {
        long now = clock.get();
        clock.addAndGet(6_000);
        // 先到 t2,t3,t5（含连续过压），再迟到 t4
        ingest.ingest("dev-C", List.of(
                p(now + 2_000, 260d, 10d, 2000d),
                p(now + 3_000, 261d, 10d, 2000d),
                p(now + 5_000, 262d, 10d, 2000d)));
        // 迟到但在允许窗口内（allowedLateness=30s）：必须接受并按采样时间归位
        IngestResult late = ingest.ingest("dev-C", List.of(
                p(now + 4_000, 210d, 10d, 2000d)));
        assertEquals(1, late.accepted(), "允许窗口内的乱序点应被接受");
        assertEquals(4, registry.get("dev-C").getSamples().size());

        // t2,t3 过压2连，t4恢复（清零），t5过压1连 → 不应触发过压（去抖生效，乱序归位正确）
        boolean overVoltageRaised = registry.get("dev-C").getAlertEvents().stream()
                .anyMatch(e -> e.type().name().equals("OVER_VOLTAGE") && e.state() == AlertState.ACTIVE);
        assertFalse(overVoltageRaised, "迟到的正常点归位后，过压连续计数应被打断");

        // 同样四个点按序一次上报到另一台设备，事件流必须完全一致（可复现）
        ingest.ingest("dev-C-ref", List.of(
                p(now + 2_000, 260d, 10d, 2000d),
                p(now + 3_000, 261d, 10d, 2000d),
                p(now + 4_000, 210d, 10d, 2000d),
                p(now + 5_000, 262d, 10d, 2000d)));
        var refEvents = registry.get("dev-C-ref").getAlertEvents();
        var gotEvents = registry.get("dev-C").getAlertEvents();
        assertEquals(describe(refEvents), describe(gotEvents), "乱序到达与顺序到达的告警结论必须一致");
        log.info("乱序重放与顺序上报事件流一致: {}", describe(gotEvents));
    }    @Test
    void timeRollbackBeyondLatenessIsRejected() {
        long now = clock.get();
        ingest.ingest("dev-D", List.of(p(now, 220d, 10d, 1d)));
        IngestResult r = ingest.ingest("dev-D", List.of(p(now - 60_000, 220d, 10d, 1d)));
        assertEquals(0, r.accepted());
        assertEquals(RejectCode.TIME_ROLLBACK, r.rejected().get(0).code());
        log.info("回退拒绝 device=dev-D code=TIME_ROLLBACK reason={}", r.rejected().get(0).reason());
    }

    private static List<String> describe(List<com.github.xingjianluo.powertelemetryalertingjava.model.AlertEvent> events) {
        // 比较语义结论（类型/状态/采样时间/依据），忽略事件编号与判定墙钟
        return events.stream()
                .map(e -> e.type() + "|" + e.state() + "|" + e.sampleTime() + "|" + e.detail())
                .toList();
    }
}
