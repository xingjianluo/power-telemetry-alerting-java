package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.model.AlertEvent;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertRuntime;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertType;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceState;
import com.github.xingjianluo.powertelemetryalertingjava.model.EffectiveConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.Sample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按采样时间顺序推进四类阈值告警状态机（过压/欠压/过流/过功率），
 * 用"连续 breachTimes 次越限才触发、连续 recoverTimes 次正常才恢复"抑制抖动。
 *
 * <p>只推进水位线之后的采样：迟到数据只修正统计窗口，不会倒着翻告警，
 * 因此乱序、重复、并发下告警结论确定、可复现。
 * SAMPLE_INTERRUPTION 由 {@link #evaluateInterruption} 基于墙钟单独推进。
 */
@Component
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    /** 评估 watermark 之后、到 upToMs（含）为止的采样，返回新产生的告警事件。 */
    public List<AlertEvent> advance(DeviceState state, EffectiveConfig cfg, long upToMs) {
        List<AlertEvent> events = new ArrayList<>();
        if (!cfg.alertsEnabled()) {
            return events;
        }
        long from = state.getAlertWatermarkMs() == Long.MIN_VALUE
                ? Long.MIN_VALUE : state.getAlertWatermarkMs() + 1;
        if (upToMs < from) {
            // 没有比水位线更新的点（迟到批次）：告警结论不变、水位线不回退。
            return events;
        }
        var tail = state.getSamples().subMap(from, true, upToMs, true);
        if (tail.isEmpty()) {
            return events;
        }
        long advancedTo = state.getAlertWatermarkMs();
        for (Sample s : tail.values()) {
            for (AlertType t : List.of(AlertType.OVER_VOLTAGE, AlertType.UNDER_VOLTAGE,
                    AlertType.OVER_CURRENT, AlertType.OVER_POWER)) {
                AlertEvent ev = evaluateThreshold(state.getDeviceId(), state.runtime(t), t, s, cfg);
                if (ev != null) {
                    events.add(ev);
                }
            }
            advancedTo = s.getTimestampMs();
        }
        state.setAlertWatermarkMs(advancedTo);
        return events;
    }

    private AlertEvent evaluateThreshold(String deviceId, AlertRuntime rt, AlertType type,
                                         Sample s, EffectiveConfig cfg) {
        boolean breach = breaches(type, s, cfg);
        rt.setLastEvaluatedMs(s.getTimestampMs());
        if (breach) {
            rt.setStreak(rt.getStreak() <= 0 ? 1 : rt.getStreak() + 1);
            if (!rt.isActive() && rt.getStreak() >= cfg.breachTimes()) {
                rt.setActive(true);
                rt.setActiveSinceMs(s.getTimestampMs());
                rt.setLastChangeMs(s.getTimestampMs());
                String basis = thresholdBasis(type, s, cfg, true)
                        + "; consecutive breach streak=" + rt.getStreak()
                        + " reached breachTimes=" + cfg.breachTimes();
                log.info("ALERT TRIGGER device={} type={} ts={} v={} a={} w={} basis='{}'",
                        deviceId, type, s.getTimestampMs(), s.getVoltageV(), s.getCurrentA(),
                        s.getPowerW(), basis);
                return event(deviceId, type, true, s, basis, cfg);
            }
        } else {
            rt.setStreak(rt.getStreak() >= 0 ? -1 : rt.getStreak() - 1);
            if (rt.isActive() && -rt.getStreak() >= cfg.recoverTimes()) {
                rt.setActive(false);
                rt.setLastChangeMs(s.getTimestampMs());
                String basis = thresholdBasis(type, s, cfg, false)
                        + "; consecutive normal streak=" + (-rt.getStreak())
                        + " reached recoverTimes=" + cfg.recoverTimes();
                log.info("ALERT RECOVER device={} type={} ts={} v={} a={} w={} basis='{}'",
                        deviceId, type, s.getTimestampMs(), s.getVoltageV(), s.getCurrentA(),
                        s.getPowerW(), basis);
                return event(deviceId, type, false, s, basis, cfg);
            }
        }
        return null;
    }

    /** 中断告警专用：基于最后采样时间与当前时间推进触发；新采样到达即恢复。 */
    public List<AlertEvent> evaluateInterruption(String deviceId, DeviceState state,
                                                 EffectiveConfig cfg, long nowMs) {
        List<AlertEvent> events = new ArrayList<>();
        AlertRuntime rt = state.runtime(AlertType.SAMPLE_INTERRUPTION);
        long lastSample = state.getLastSampleMs();
        boolean stale = lastSample > 0 && nowMs - lastSample > cfg.interruptionTimeoutMs();
        if (!cfg.alertsEnabled()) {
            return events;
        }
        if (stale && !rt.isActive()) {
            rt.setActive(true);
            rt.setActiveSinceMs(lastSample + cfg.interruptionTimeoutMs());
            rt.setLastChangeMs(nowMs);
            String basis = "no sample for " + (nowMs - lastSample) + "ms, lastSampleMs=" + lastSample
                    + ", interruptionTimeoutMs=" + cfg.interruptionTimeoutMs();
            log.info("ALERT TRIGGER device={} type=SAMPLE_INTERRUPTION now={} basis='{}'",
                    deviceId, nowMs, basis);
            events.add(new AlertEvent(eventId(deviceId, AlertType.SAMPLE_INTERRUPTION, true, nowMs),
                    deviceId, AlertType.SAMPLE_INTERRUPTION, true, nowMs, 0, 0, 0, basis));
        } else if (!stale && rt.isActive()) {
            rt.setActive(false);
            rt.setStreak(0);
            rt.setLastChangeMs(nowMs);
            String basis = "fresh sample received at lastSampleMs=" + lastSample
                    + ", within interruptionTimeoutMs=" + cfg.interruptionTimeoutMs();
            log.info("ALERT RECOVER device={} type=SAMPLE_INTERRUPTION now={} basis='{}'",
                    deviceId, nowMs, basis);
            events.add(new AlertEvent(eventId(deviceId, AlertType.SAMPLE_INTERRUPTION, false, nowMs),
                    deviceId, AlertType.SAMPLE_INTERRUPTION, false, nowMs, 0, 0, 0, basis));
        }
        return events;
    }

    /** 停用告警时恢复全部活动告警并清零防抖计数，保证重新启用时状态可预测。 */
    public List<AlertEvent> deactivateAll(String deviceId, DeviceState state, long nowMs) {
        List<AlertEvent> events = new ArrayList<>();
        for (AlertType t : AlertType.values()) {
            AlertRuntime rt = state.runtime(t);
            rt.setStreak(0);
            if (rt.isActive()) {
                rt.setActive(false);
                rt.setLastChangeMs(nowMs);
                String basis = "alerts disabled for device; active alert force-recovered";
                events.add(new AlertEvent(eventId(deviceId, t, false, nowMs),
                        deviceId, t, false, nowMs, 0, 0, 0, basis));
                log.info("ALERT RECOVER(device disabled) device={} type={} now={}", deviceId, t, nowMs);
            }
        }
        return events;
    }

    /** 单个采样点对某类告警是否越限。 */
    public boolean breaches(AlertType type, Sample s, EffectiveConfig cfg) {
        return switch (type) {
            case OVER_VOLTAGE -> s.getVoltageV() > cfg.overVoltageV();
            case UNDER_VOLTAGE -> s.getVoltageV() < cfg.underVoltageV();
            case OVER_CURRENT -> s.getCurrentA() > cfg.overCurrentA();
            case OVER_POWER -> s.getPowerW() > cfg.overPowerW();
            case SAMPLE_INTERRUPTION -> false;
        };
    }

    private String thresholdBasis(AlertType type, Sample s, EffectiveConfig cfg, boolean breach) {
        return switch (type) {
            case OVER_VOLTAGE -> "voltageV=" + s.getVoltageV() + (breach ? " > " : " <= ")
                    + "overVoltageV=" + cfg.overVoltageV();
            case UNDER_VOLTAGE -> "voltageV=" + s.getVoltageV() + (breach ? " < " : " >= ")
                    + "underVoltageV=" + cfg.underVoltageV();
            case OVER_CURRENT -> "currentA=" + s.getCurrentA() + (breach ? " > " : " <= ")
                    + "overCurrentA=" + cfg.overCurrentA();
            case OVER_POWER -> "powerW=" + s.getPowerW() + (breach ? " > " : " <= ")
                    + "overPowerW=" + cfg.overPowerW();
            case SAMPLE_INTERRUPTION -> "interruption evaluated by wall clock";
        };
    }

    private AlertEvent event(String deviceId, AlertType type, boolean triggered, Sample s,
                             String basis, EffectiveConfig cfg) {
        return new AlertEvent(eventId(deviceId, type, triggered, s.getTimestampMs()),
                deviceId, type, triggered, s.getTimestampMs(),
                s.getVoltageV(), s.getCurrentA(), s.getPowerW(), basis);
    }

    /** 事件 ID 由设备+类型+触发/恢复+时间确定，重复推进不会产生重复事件。 */
    static String eventId(String deviceId, AlertType type, boolean triggered, long atMs) {
        return deviceId + ":" + type + ":" + (triggered ? "TRIGGER" : "RECOVER") + ":" + atMs;
    }
}
