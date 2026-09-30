package com.github.xingjianluo.powertelemetryalertingjava.tests.engine;

import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceState;
import com.github.xingjianluo.powertelemetryalertingjava.model.IngestBatchResult;
import com.github.xingjianluo.powertelemetryalertingjava.model.RawSample;
import com.github.xingjianluo.powertelemetryalertingjava.model.RecordStatus;
import com.github.xingjianluo.powertelemetryalertingjava.tests.support.InMemoryStateRepository;
import com.github.xingjianluo.powertelemetryalertingjava.tests.support.ManualClock;
import com.github.xingjianluo.powertelemetryalertingjava.tests.support.TestFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 同一设备多批次并发上报：不重不漏、计数一致；不同设备互不阻塞；配置调整不影响接入。 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private static final long T0 = 1_000_000_000_000L;

    private RawSample raw(long ts, double v, double a, double w) {
        RawSample r = new RawSample();
        r.setTimestampMs(ts);
        r.setVoltageV(v);
        r.setCurrentA(a);
        r.setPowerW(w);
        return r;
    }

    @Test
    @DisplayName("同设备并发批次：每个采样点恰好计一次，重复点全部幂等，总数与顺序写入一致")
    void concurrentBatchesForSameDeviceAreConsistent() throws Exception {
        ManualClock clock = new ManualClock(T0 + 3_600_000L);
        ConfigProperties props = new ConfigProperties();
        props.validate();
        InMemoryStateRepository repo = new InMemoryStateRepository();
        DeviceRegistry registry = TestFactory.registry(props, repo, clock);

        int threads = 16;
        int perThread = 200;
        // 所有时间戳都落在墙钟前 ~200s 内（远小于 reorderLag 10min），模拟同一近线时间窗的并发重放
        long base = T0 + 3_400_000L;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        AtomicInteger totalAccepted = new AtomicInteger();
        AtomicInteger totalDuplicate = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int threadNo = t;
            pool.submit(() -> {
                try {
                    start.await();
                    List<RawSample> batch = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        // 所有线程共享的 perThread 个时间戳（1s 间隔），大量并发重复
                        long ts = base + i * 1_000L;
                        batch.add(raw(ts, 220.0, 5.0, 100.0 + (i % 7)));
                        // 一半线程额外携带该线程独有的新时间戳点（每个 i 的秒内偏移互不碰撞）
                        if (threadNo % 2 == 0) {
                            long uniqueTs = base + i * 1_000L + (long) (threadNo / 2 + 1) * 100L;
                            batch.add(raw(uniqueTs, 220.0, 5.0, 100.0 + (i % 7)));
                        }
                    }
                    IngestBatchResult r = registry.ingest("dev-conc", batch);
                    totalAccepted.addAndGet(r.accepted());
                    totalDuplicate.addAndGet(r.duplicate());
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdownNow();

        assertTrue(errors.isEmpty(), "no thread should fail: " + errors);
        DeviceState state = registry.state("dev-conc").orElseThrow();

        // 每个 i 贡献 1 个共享点 + 8 个偶号线程各自 1 个独有偏移点（100..800ms，不跨秒碰撞）
        int expectedUnique = perThread * (1 + threads / 2);
        assertEquals(expectedUnique, state.getSamples().size(),
                "each unique timestamp stored exactly once");
        assertEquals(expectedUnique, totalAccepted.get());
        // 共享点在 16 个线程间重放 15 次；独有点各只出现一次
        int expectedDuplicates = perThread * (threads - 1);
        assertEquals(expectedDuplicates, totalDuplicate.get(),
                "every replay of an identical reading is idempotent");

        // 所有窗口计数之和 == 唯一点数；平均/峰值重算无副作用
        int windowCount = state.getWindows().values().stream()
                .mapToInt(com.github.xingjianluo.powertelemetryalertingjava.model.WindowData::getCount)
                .sum();
        assertEquals(state.getSamples().size(), windowCount,
                "window counts must add up to unique samples, no double counting");
        log.info("concurrency ok: uniqueSamples={} accepted={} duplicate={} windows={} windowPoints={}",
                state.getSamples().size(), totalAccepted.get(), totalDuplicate.get(),
                state.getWindows().size(), windowCount);
    }

    @Test
    @DisplayName("并发期间修改某设备阈值不影响其他设备接入，非法配置被拒绝且原状态不变")
    void configChangeIsolationAndValidation() throws Exception {
        ManualClock clock = new ManualClock(T0 + 3_600_000L);
        ConfigProperties props = new ConfigProperties();
        props.validate();
        InMemoryStateRepository repo = new InMemoryStateRepository();
        DeviceRegistry registry = TestFactory.registry(props, repo, clock);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        // 4 个设备持续上报
        for (int d = 0; d < 4; d++) {
            final String device = "dev-" + d;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        registry.ingest(device, List.of(raw(T0 + i * 1_000L, 220, 5, 100)));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        // 其余线程反复改 dev-0 配置（含非法值）
        for (int k = 0; k < 4; k++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        DeviceConfig patch = new DeviceConfig();
                        if (i % 3 == 0) {
                            patch.setOverVoltageV(100.0); // 非法：低于欠压默认值
                            assertThrows(IllegalArgumentException.class,
                                    () -> registry.updateConfig("dev-0", patch, false));
                        } else {
                            patch.setOverVoltageV(250.0);
                            registry.updateConfig("dev-0", patch, false);
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertTrue(errors.isEmpty(), errors.toString());

        for (int d = 0; d < 4; d++) {
            DeviceState s = registry.state("dev-" + d).orElseThrow();
            assertEquals(100, s.getSamples().size(), "dev-" + d + " keeps all 100 points");
        }
        log.info("config isolation ok, sample counts: dev-0={} dev-1={} dev-2={} dev-3={}",
                registry.state("dev-0").orElseThrow().getSamples().size(),
                registry.state("dev-1").orElseThrow().getSamples().size(),
                registry.state("dev-2").orElseThrow().getSamples().size(),
                registry.state("dev-3").orElseThrow().getSamples().size());
    }

    @Test
    @DisplayName("重复并发重放：先全部写入，再并发重放同样批次，状态不发生变化")
    void concurrentReplayDoesNotChangeState() throws Exception {
        ManualClock clock = new ManualClock(T0 + 3_600_000L);
        ConfigProperties props = new ConfigProperties();
        props.validate();
        InMemoryStateRepository repo = new InMemoryStateRepository();
        DeviceRegistry registry = TestFactory.registry(props, repo, clock);

        List<RawSample> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            batch.add(raw(T0 + i * 1_000L, 220, 5, 100 + i));
        }
        registry.ingest("dev-replay", batch);
        DeviceState before = registry.state("dev-replay").orElseThrow();
        int sampleCount = before.getSamples().size();
        long revisionBefore = before.getRevision();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    IngestBatchResult r = registry.ingest("dev-replay", batch);
                    assertEquals(0, r.accepted());
                    assertEquals(100, r.duplicate());
                    assertTrue(r.records().stream().allMatch(x -> x.status() != RecordStatus.REJECTED));
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertTrue(errors.isEmpty(), errors.toString());

        DeviceState after = registry.state("dev-replay").orElseThrow();
        assertEquals(sampleCount, after.getSamples().size());
        assertEquals(revisionBefore, after.getRevision(), "pure-duplicate batch must not advance revision");
    }
}
