package com.github.xingjianluo.powertelemetryalertingjava.monitor;

import com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时扫描采样中断，驱动 SAMPLE_INTERRUPTION 告警的触发与恢复。
 * 判定只依赖"最后采样时间 + 墙钟"，因此服务重启后状态同样可预测。
 */
@Component
public class InterruptionMonitor {

    private static final Logger log = LoggerFactory.getLogger(InterruptionMonitor.class);

    private final DeviceRegistry registry;

    public InterruptionMonitor(DeviceRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${power.telemetry.tick-interval-ms:1000}")
    public void tick() {
        try {
            List<String> changed = registry.tickAll();
            if (!changed.isEmpty()) {
                log.info("interruption tick changed devices: {}", changed);
            }
        } catch (RuntimeException e) {
            // 单轮扫描失败不能杀死调度线程。
            log.error("interruption tick failed: {}", e.getMessage(), e);
        }
    }
}
