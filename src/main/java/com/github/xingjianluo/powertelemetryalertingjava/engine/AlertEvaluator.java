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

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 基于阈值与连续计数去抖评估过压/欠压/过流/过功率四类告警。
 *
 * <p>规则（消除短暂抖动导致的横跳）：连续 N 个采样点违规才触发；
 * 告警中连续 M 个采样点恢复才解除。未达计数只推进计数、不产生事件。</p>
 *
 * <p>乱序点按采样时间顺序依次喂入状态机（{@link IngestService} 保证），
 * 因此同一批数据以任意到达顺序处理，结论一致、可复现。</p>
 */
@Component
public class AlertEvaluator {

    private final LongSupplier clock;
    private final ServiceProperties properties;

    public AlertEvaluator(LongSupplier clock, ServiceProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public List<AlertEvent> evaluate(DeviceState state, TelemetrySample sample,
                                     int triggerConsecutive, int recoverConsecutive) {
        List<AlertEvent> events = new ArrayList<>(2);
        if (!state.getConfig().alertsEnabled()) {
            return events;
        }
        Thresholds t = resolveThresholds(state.getConfig().thresholds());

        check(state, sample, AlertType.OVER_VOLTAGE,
                sample.voltage() > nv(t.overVoltage()),
                "电压 %.2fV > 过压阈值 %.2fV，连续 %d 点".formatted(sample.voltage(), t.overVoltage(), triggerConsecutive),
                "电压 %.2fV 回落至过压阈值 %.2fV 以下，连续 %d 点恢复".formatted(sample.voltage(), t.overVoltage(), recoverConsecutive),
                triggerConsecutive, recoverConsecutive, events);
        check(state, sample, AlertType.UNDER_VOLTAGE,
                sample.voltage() < nv(t.underVoltage()),
                "电压 %.2fV < 欠压阈值 %.2fV，连续 %d 点".formatted(sample.voltage(), t.underVoltage(), triggerConsecutive),
                "电压 %.2fV 回升至欠压阈值 %.2fV 以上，连续 %d 点恢复".formatted(sample.voltage(), t.underVoltage(), recoverConsecutive),
                triggerConsecutive, recoverConsecutive, events);
        check(state, sample, AlertType.OVER_CURRENT,
                sample.current() > nv(t.overCurrent()),
                "电流 %.2fA > 过流阈值 %.2fA，连续 %d 点".formatted(sample.current(), t.overCurrent(), triggerConsecutive),
                "电流 %.2fA 回落至过流阈值 %.2fA 以下，连续 %d 点恢复".formatted(sample.current(), t.overCurrent(), recoverConsecutive),
                triggerConsecutive, recoverConsecutive, events);
        check(state, sample, AlertType.OVER_POWER,
                sample.power() > nv(t.overPower()),
                "功率 %.2fW > 过功率阈值 %.2fW，连续 %d 点".formatted(sample.power(), t.overPower(), triggerConsecutive),
                "功率 %.2fW 回落至过功率阈值 %.2fW 以下，连续 %d 点恢复".formatted(sample.power(), t.overPower(), recoverConsecutive),
                triggerConsecutive, recoverConsecutive, events);
        return events;
    }

    /** 设备未单独配置的阈值项回落到全局默认；maxSampleGap 为 null 表示关闭。 */
    public Thresholds resolveThresholds(Thresholds device) {
        Double ov = device.overVoltage() != null ? device.overVoltage() : properties.getDefaultOverVoltage();
        Double uv = device.underVoltage() != null ? device.underVoltage() : properties.getDefaultUnderVoltage();
        Double oc = device.overCurrent() != null ? device.overCurrent() : properties.getDefaultOverCurrent();
        Double op = device.overPower() != null ? device.overPower() : properties.getDefaultOverPower();
        Long gap = device.maxSampleGap() != null ? device.maxSampleGap() : properties.getDefaultMaxSampleGap();
        return new Thresholds(ov, uv, oc, op, gap);
    }

    private static double nv(Double v) {
        return v == null ? Double.NaN : v;
    }

    private void check(DeviceState state, TelemetrySample sample, AlertType type,
                       boolean violating, String raiseDetail, String clearDetail,
                       int triggerN, int recoverN, List<AlertEvent> events) {
        AlertStatus st = state.getAlertStatuses().computeIfAbsent(type, k -> new AlertStatus());
        if (violating) {
            st.setRecoverCount(0);
            if (!st.isActive()) {
                st.setViolateCount(st.getViolateCount() + 1);
                if (st.getViolateCount() >= triggerN) {
                    raise(state, st, type, sample, raiseDetail, events);
                }
            }
        } else {
            st.setViolateCount(0);
            if (st.isActive()) {
                st.setRecoverCount(st.getRecoverCount() + 1);
                if (st.getRecoverCount() >= recoverN) {
                    clear(state, st, type, sample, clearDetail, events);
                }
            }
        }
    }

    private void raise(DeviceState state, AlertStatus st, AlertType type,
                       TelemetrySample sample, String detail, List<AlertEvent> events) {
        st.setActive(true);
        st.setViolateCount(0);
        st.setActiveSinceSample(sample.sampleTime());
        long id = nextEventId(state);
        AlertEvent event = new AlertEvent(id, state.getConfig().deviceId(), type,
                AlertState.ACTIVE, sample.sampleTime(), clock.getAsLong(), detail);
        state.appendAlertEvent(event);
        st.setLastEventId(id);
        events.add(event);
    }

    private void clear(DeviceState state, AlertStatus st, AlertType type,
                       TelemetrySample sample, String detail, List<AlertEvent> events) {
        st.setActive(false);
        st.setRecoverCount(0);
        long id = nextEventId(state);
        AlertEvent event = new AlertEvent(id, state.getConfig().deviceId(), type,
                AlertState.RECOVERED, sample.sampleTime(), clock.getAsLong(), detail);
        state.appendAlertEvent(event);
        st.setLastEventId(id);
        events.add(event);
    }

    private static long nextEventId(DeviceState state) {
        long seq = state.getAlertEventSeq() + 1;
        state.setAlertEventSeq(seq);
        return seq;
    }
}
