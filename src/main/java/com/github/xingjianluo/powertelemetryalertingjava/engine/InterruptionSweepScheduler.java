package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.config.ServiceProperties;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertEvent;
import com.github.xingjianluo.powertelemetryalertingjava.persistence.SnapshotStore;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 墙钟扫描采样中断：服务就绪后按固定周期扫描全部设备。
 * 扫描与上报共用设备锁，不会与接入批次竞争；产生中断事件后落盘。
 */
@Component
public class InterruptionSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(InterruptionSweepScheduler.class);

    private final DeviceRegistry registry;
    private final InterruptionMonitor monitor;
    private final ServiceProperties properties;
    private final SnapshotStore snapshotStore;
    private final LongSupplier clock;
    private ScheduledExecutorService executor;

    public InterruptionSweepScheduler(DeviceRegistry registry,
                                      InterruptionMonitor monitor,
                                      ServiceProperties properties,
                                      SnapshotStore snapshotStore,
                                      LongSupplier clock) {
        this.registry = registry;
        this.monitor = monitor;
        this.properties = properties;
        this.snapshotStore = snapshotStore;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        long period = properties.getInterruptionSweepMs();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "interruption-sweep");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::sweepOnce, period, period, TimeUnit.MILLISECONDS);
        log.info("采样中断墙钟扫描已启动 periodMs={}", period);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** 执行一轮扫描（也供测试直接调用）。 */
    public void sweepOnce() {
        long now = clock.getAsLong();
        boolean changed = false;
        for (DeviceState state : registry.all()) {
            state.getLock().lock();
            try {
                List<AlertEvent> events = monitor.sweep(state, now);
                if (!events.isEmpty()) {
                    changed = true;
                    for (AlertEvent e : events) {
                        log.info("墙钟扫描触发 device={} type={} sampleTime={} detail={}",
                                e.deviceId(), e.type(), e.sampleTime(), e.detail());
                    }
                }
            } finally {
                state.getLock().unlock();
            }
        }
        if (changed && properties.isSnapshotAfterEachBatch()) {
            snapshotStore.save(registry);
        }
    }
}
