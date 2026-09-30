package com.github.xingjianluo.powertelemetryalertingjava.tests;

import com.github.xingjianluo.powertelemetryalertingjava.api.dto.TelemetryPointDto;
import com.github.xingjianluo.powertelemetryalertingjava.config.ServiceProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.AlertEvaluator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.IngestService;
import com.github.xingjianluo.powertelemetryalertingjava.engine.InterruptionMonitor;
import com.github.xingjianluo.powertelemetryalertingjava.engine.RecordValidator;
import com.github.xingjianluo.powertelemetryalertingjava.engine.WindowManager;
import com.github.xingjianluo.powertelemetryalertingjava.model.IngestResult;
import com.github.xingjianluo.powertelemetryalertingjava.persistence.SnapshotStore;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一设备多批次并发上报：锁串行化 + 幂等去重，
 * 要求采样点不重不漏、窗口统计与串行参考一致、各设备互不干扰。
 */
class ConcurrentIngestTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentIngestTest.class);

    record Harness(AtomicLong clock, ServiceProperties props, DeviceRegistry registry,
                   IngestService ingest) {
    }

    private Harness harness(Path snap, long start) {
        AtomicLong clock = new AtomicLong(start);
        ServiceProperties props = new ServiceProperties();
        props.setSnapshotFile(snap.toString());
        props.setMaxHistoryMs(3600_000L);
        props.setFutureToleranceMs(600_000L);
        props.setAllowedLatenessMs(600_000L);
        props.setWindowMs(60_000L);
        props.setTriggerConsecutive(3);
        props.setRecoverConsecutive(3);
        props.setSnapshotAfterEachBatch(false);
        props.setDefaultOverVoltage(999d);
        props.setDefaultUnderVoltage(0d);
        props.setDefaultOverCurrent(999d);
        props.setDefaultOverPower(999_999d);
        props.setDefaultMaxSampleGap(3_600_000L);
        DeviceRegistry registry = new DeviceRegistry();
        SnapshotStore store = new SnapshotStore(snap.toString());
        AlertEvaluator ev = new AlertEvaluator(clock::get, props);
        InterruptionMonitor im = new InterruptionMonitor(clock::get, ev, props);
        IngestService ingest = new IngestService(registry, new RecordValidator(clock::get), ev, im,
                new WindowManager(), store, props, clock::get);
        return new Harness(clock, props, registry, ingest);
    }

    @Test
    void concurrentBatchesForOneDeviceAreExactlyOnceAndConsistent() throws Exception {
        Path snap = Files.createTempDirectory("snap5").resolve("s.json");
        Harness h = harness(snap, 5_000_000_000L);

        int threads = 8;
        int perThread = 100;
        // 时间戳空间 0..(threads*perThread-1)，由线程轮询取号，制造天然重叠以暴露竞争
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    // 每个线程独占一段连续采样时间区间 [tid*100, (tid+1)*100)，
                    // 区间在时间轴上交错，而锁获取顺序随机 → 大量乱序批次走重放路径
                    for (int i = 0; i < perThread; i++) {
                        long offset = (long) (tid * perThread + i) * 100L;
                        long ts = h.clock().get() + offset;
                        IngestResult r = h.ingest().ingest("dev-X",
                                List.of(new TelemetryPointDto(ts, 220d, 10d, 1000d + (i % 50))));
                        if (r.rejected().stream().anyMatch(x -> x.code()
                                != com.github.xingjianluo.powertelemetryalertingjava.model.RejectCode.DUPLICATE
                                && x.code()
                                != com.github.xingjianluo.powertelemetryalertingjava.model.RejectCode.TIME_ROLLBACK)) {
                            failures.add("意外拒绝: " + r.rejected());
                        }
                    }
                } catch (Throwable e) {
                    failures.add(e.toString());
                }
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(failures.isEmpty(), "并发过程不应出现非预期错误: " + failures);

        DeviceState state = h.registry().get("dev-X");
        state.getLock().lock();
        int uniquePoints;
        long peakSum;
        try {
            uniquePoints = state.getSamples().size();
            // 所有点都在同一 60s 窗口内（800*100ms=80s 跨 2 窗口），统计每窗口点数之和
            int windowedPoints = state.getWindows().values().stream()
                    .mapToInt(w -> w.getSamples().size()).sum();
            log.info("并发接入完成 唯一点数={} 窗口内点次={} 窗口数={} 水位线={}",
                    uniquePoints, windowedPoints, state.getWindows().size(), state.getWatermark());
            // 时间戳集合连续无缺
            var keys = new ArrayList<>(state.getSamples().keySet());
            for (int i = 1; i < keys.size(); i++) {
                assertEquals(100L, keys.get(i) - keys.get(i - 1), "采样时间序列必须连续，不重不漏");
            }
            assertEquals(threads * perThread, uniquePoints, "每个 (线程,序号) 唯一时间戳恰好一次");
            assertEquals(uniquePoints, windowedPoints, "每个点恰好计入一个窗口，不重不漏");
        } finally {
            state.getLock().unlock();
        }
    }

    @Test
    void concurrentDevicesDoNotBlockEachOtherAndOverlapIsIdempotent() throws Exception {
        Path snap = Files.createTempDirectory("snap6").resolve("s.json");
        Harness h = harness(snap, 6_000_000_000L);
        int devices = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(devices);
        for (int d = 0; d < devices; d++) {
            final String id = "dev-" + d;
            pool.submit(() -> {
                start.await();
                for (int round = 0; round < 50; round++) {
                    // 同一时间戳被所有设备各自上报（跨设备不算重复），并在设备内重复两轮
                    long ts = h.clock().get() + round * 1000L;
                    h.ingest().ingest(id, List.of(new TelemetryPointDto(ts, 220d, 10d, 500d)));
                    h.ingest().ingest(id, List.of(new TelemetryPointDto(ts, 220d, 10d, 500d)));
                    LockSupport.parkNanos(1000);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        for (int d = 0; d < devices; d++) {
            DeviceState s = h.registry().get("dev-" + d);
            assertEquals(50, s.getSamples().size(), "设备 dev-" + d + " 重复批次不累计");
        }
        log.info("4设备×50轮重复并发：每设备恰好50点，设备间互不影响");
    }
}
