package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.config.ServiceProperties;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertEvent;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertState;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertStatus;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertType;
import com.github.xingjianluo.powertelemetryalertingjava.model.TelemetrySample;
import com.github.xingjianluo.powertelemetryalertingjava.model.Thresholds;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * 采样中断告警：
 * <ul>
 *   <li>新采样到达时，若与上一采样点的采样时间间隔超过 maxSampleGap 则触发；
 *       中断后第一个新采样到达即恢复（恢复条件天然确定，不需要计数去抖）。</li>
 *   <li>墙钟扫描：距最后一个采样点已超过 maxSampleGap 且尚未告警则触发，
 *       用于设备彻底不发数据的场景；与间隔检测共用同一 AlertStatus，不会重复触发。</li>
 * </ul>
 */
@Component
public class InterruptionMonitor {

    private final LongSupplier clock;
    private final AlertEvaluator evaluator;
    private final ServiceProperties properties;

    public InterruptionMonitor(LongSupplier clock, AlertEvaluator evaluator,
                               ServiceProperties properties) {
        this.clock = clock;
        this.evaluator = evaluator;
        this.properties = properties;
    }

    public List<AlertEvent> onSample(DeviceState state, TelemetrySample previous,
                                     TelemetrySample current) {
        AlertStatus st = state.getAlertStatuses()
                .computeIfAbsent(AlertType.SAMPLE_INTERRUPT, k -> new AlertStatus());
        if (st.isActive()) {
            // 中断后重新收到采样 → 立即恢复
            st.setActive(false);
            st.setViolateCount(0);
            st.setRecoverCount(0);
            return List.of(raiseOrClear(state, st, AlertState.RECOVERED, current.sampleTime(),
                    "采样恢复：于采样时间 %d 收到新数据点".formatted(current.sampleTime())));
        }
        if (previous != null) {
            long gap = current.sampleTime() - previous.sampleTime();
            Long maxGap = resolveGap(state);
            if (maxGap != null && gap > maxGap) {
                st.setActive(true);
                return List.of(raiseOrClear(state, st, AlertState.ACTIVE, current.sampleTime(),
                        "采样中断：与上一点 %d 间隔 %dms 超过最大允许 %dms"
                                .formatted(previous.sampleTime(), gap, maxGap)));
            }
        }
        return List.of();
    }

    public List<AlertEvent> sweep(DeviceState state, long now) {
        if (!state.getConfig().alertsEnabled()) {
            return List.of();
        }
        Long maxGap = resolveGap(state);
        if (maxGap == null || state.getSamples().isEmpty()) {
            return List.of();
        }
        AlertStatus st = state.getAlertStatuses()
                .computeIfAbsent(AlertType.SAMPLE_INTERRUPT, k -> new AlertStatus());
        if (st.isActive()) {
            return List.of();
        }
        long lastSample = state.getSamples().lastKey();
        long gap = now - lastSample;
        if (gap > maxGap) {
            st.setActive(true);
            return List.of(raiseOrClear(state, st, AlertState.ACTIVE, lastSample,
                    "采样中断：墙钟扫描距最后采样时间 %d 已 %dms，超过最大允许 %dms"
                            .formatted(lastSample, gap, maxGap)));
        }
        return List.of();
    }

    private Long resolveGap(DeviceState state) {
        Thresholds t = evaluator.resolveThresholds(state.getConfig().thresholds());
        return t.maxSampleGap();
    }

    private AlertEvent raiseOrClear(DeviceState state, AlertStatus st, AlertState as,
                                    long sampleTime, String detail) {
        long seq = state.getAlertEventSeq() + 1;
        state.setAlertEventSeq(seq);
        AlertEvent event = new AlertEvent(seq, state.getConfig().deviceId(),
                AlertType.SAMPLE_INTERRUPT, as, sampleTime, clock.getAsLong(), detail);
        state.appendAlertEvent(event);
        st.setLastEventId(seq);
        return event;
    }
}
