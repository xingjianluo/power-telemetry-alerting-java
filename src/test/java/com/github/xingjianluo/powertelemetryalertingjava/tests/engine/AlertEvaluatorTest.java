package com.github.xingjianluo.powertelemetryalertingjava.tests.engine;

import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.AlertEvaluator;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertEvent;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertRuntime;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertType;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceState;
import com.github.xingjianluo.powertelemetryalertingjava.model.EffectiveConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.Sample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阈值告警状态机：连续计数防抖、触发、恢复、迟到点不倒翻、中断判定、停用即恢复。 */
class AlertEvaluatorTest {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluatorTest.class);

    private final AlertEvaluator evaluator = new AlertEvaluator();
    private ConfigProperties props;
    private EffectiveConfig cfg;

    @BeforeEach
    void setUp() {
        props = new ConfigProperties();
        props.validate();
        cfg = EffectiveConfig.merge(props, null);
    }

    private Sample sample(long ts, double v, double a, double w) {
        return new Sample(ts, v, a, w);
    }

    private DeviceState stateWith(String device, Sample... samples) {
        DeviceState state = new DeviceState(device);
        for (Sample s : samples) {
            state.getSamples().put(s.getTimestampMs(), s);
            if (s.getTimestampMs() > state.getLastSampleMs()) {
                state.setLastSampleMs(s.getTimestampMs());
            }
        }
        return state;
    }

    @Test
    @DisplayName("过压抖动：1~2 次越限不告警，连续 3 次才触发；恢复同样需连续 3 次")
    void debounceTriggerAndRecover() {
        DeviceState state = stateWith("dev-1");
        long t = 1_000;

        // 越限 1 次：不触发
        state.getSamples().put(t, sample(t, 260, 5, 1000));
        assertTrue(evaluator.advance(state, cfg, t).isEmpty());
        assertFalse(state.runtime(AlertType.OVER_VOLTAGE).isActive());

        // 回到正常，计数被打断
        t += 1_000;
        state.getSamples().put(t, sample(t, 220, 5, 1000));
        assertTrue(evaluator.advance(state, cfg, t).isEmpty());

        // 连续 2 次越限：仍不触发
        for (int i = 0; i < 2; i++) {
            t += 1_000;
            state.getSamples().put(t, sample(t, 260, 5, 1000));
            assertTrue(evaluator.advance(state, cfg, t).isEmpty(), "streak " + (i + 1));
        }
        // 第 3 次连续越限：触发
        t += 1_000;
        state.getSamples().put(t, sample(t, 260, 5, 1000));
        List<AlertEvent> triggered = evaluator.advance(state, cfg, t);
        assertEquals(1, triggered.size());
        assertTrue(triggered.get(0).isTriggered());
        assertEquals(AlertType.OVER_VOLTAGE, triggered.get(0).getType());
        assertTrue(triggered.get(0).getBasis().contains("voltageV=260.0 > overVoltageV=253.0"),
                "basis must explain the decision: " + triggered.get(0).getBasis());
        log.info("triggered event: device={} ts={} basis='{}'", triggered.get(0).getDeviceId(),
                triggered.get(0).getOccurredAtMs(), triggered.get(0).getBasis());

        // 活动中，抖动 1 次正常不恢复
        t += 1_000;
        state.getSamples().put(t, sample(t, 220, 5, 1000));
        assertTrue(evaluator.advance(state, cfg, t).isEmpty());
        t += 1_000;
        state.getSamples().put(t, sample(t, 260, 5, 1000));
        assertTrue(evaluator.advance(state, cfg, t).isEmpty());
        assertTrue(state.runtime(AlertType.OVER_VOLTAGE).isActive());

        // 连续 3 次正常：恢复
        List<AlertEvent> recovered = List.of();
        for (int i = 0; i < 3; i++) {
            t += 1_000;
            state.getSamples().put(t, sample(t, 220, 5, 1000));
            recovered = evaluator.advance(state, cfg, t);
        }
        assertEquals(1, recovered.size());
        assertFalse(recovered.get(0).isTriggered());
        assertFalse(state.runtime(AlertType.OVER_VOLTAGE).isActive());
        log.info("recovered event: ts={} basis='{}'", recovered.get(0).getOccurredAtMs(),
                recovered.get(0).getBasis());
    }

    @Test
    @DisplayName("迟到（水位线之前）的采样只更新数据，绝不倒翻告警")
    void lateSamplesDoNotRewindAlerts() {
        DeviceState state = stateWith("dev-2");
        long base = 10_000;
        // 先在 base..base+3s 连续 3 次过压触发
        for (int i = 0; i < 3; i++) {
            long ts = base + i * 1_000L;
            state.getSamples().put(ts, sample(ts, 260, 5, 1000));
            evaluator.advance(state, cfg, ts);
        }
        assertTrue(state.runtime(AlertType.OVER_VOLTAGE).isActive());
        long watermark = state.getAlertWatermarkMs();

        // 来一个更早的正常迟到点：不应产生任何事件，水位线不回退
        long late = base - 5_000L;
        state.getSamples().put(late, sample(late, 220, 5, 1000));
        List<AlertEvent> events = evaluator.advance(state, cfg, state.getLastSampleMs());
        assertTrue(events.isEmpty());
        assertEquals(watermark, state.getAlertWatermarkMs());
        assertTrue(state.runtime(AlertType.OVER_VOLTAGE).isActive());
    }

    @Test
    @DisplayName("欠压/过流/过功率各自独立判定，可同点同时触发")
    void allThresholdTypesEvaluateIndependently() {
        DeviceState state = stateWith("dev-3");
        // 连续 3 个极端点：同时欠压、过流、过功率
        List<AlertEvent> all = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long ts = 2_000 + i * 1_000L;
            state.getSamples().put(ts, sample(ts, 100, 20, 5000));
            all.addAll(evaluator.advance(state, cfg, ts));
        }
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(e -> e.getType() == AlertType.UNDER_VOLTAGE));
        assertTrue(all.stream().anyMatch(e -> e.getType() == AlertType.OVER_CURRENT));
        assertTrue(all.stream().anyMatch(e -> e.getType() == AlertType.OVER_POWER));
        for (AlertEvent e : all) {
            log.info("event type={} basis='{}'", e.getType(), e.getBasis());
        }
    }

    @Test
    @DisplayName("采样中断：超时未上报触发，收到新点恢复；重启后状态可预测")
    void interruptionTriggerAndRecover() {
        DeviceState state = stateWith("dev-4");
        state.setLastSampleMs(100_000L);

        // 300s 无数据：触发
        List<AlertEvent> tripped = evaluator.evaluateInterruption("dev-4", state, cfg, 400_001L);
        assertEquals(1, tripped.size());
        assertTrue(tripped.get(0).isTriggered());
        assertEquals(AlertType.SAMPLE_INTERRUPTION, tripped.get(0).getType());
        AlertRuntime rt = state.runtime(AlertType.SAMPLE_INTERRUPTION);
        assertTrue(rt.isActive());

        // 再扫描一次不重复触发
        assertTrue(evaluator.evaluateInterruption("dev-4", state, cfg, 401_000L).isEmpty());

        // 新数据到达（最后采样时间更新）：恢复
        state.setLastSampleMs(401_000L);
        List<AlertEvent> recovered = evaluator.evaluateInterruption("dev-4", state, cfg, 401_500L);
        assertEquals(1, recovered.size());
        assertFalse(recovered.get(0).isTriggered());
        assertFalse(rt.isActive());
        log.info("interruption basis: trigger='{}' recover='{}'",
                tripped.get(0).getBasis(), recovered.get(0).getBasis());
    }

    @Test
    @DisplayName("停用告警：所有活动告警立即恢复且防抖清零")
    void disablingAlertsForceRecovers() {
        DeviceState state = stateWith("dev-5");
        for (int i = 0; i < 3; i++) {
            long ts = i * 1_000L;
            state.getSamples().put(ts, sample(ts, 260, 20, 5000));
            evaluator.advance(state, cfg, ts);
        }
        assertTrue(state.runtime(AlertType.OVER_VOLTAGE).isActive());
        assertTrue(state.runtime(AlertType.OVER_CURRENT).isActive());

        List<AlertEvent> recovered = evaluator.deactivateAll("dev-5", state, 5_000L);
        assertEquals(3, recovered.size());
        assertTrue(recovered.stream().noneMatch(AlertEvent::isTriggered));
        assertFalse(state.runtime(AlertType.OVER_VOLTAGE).isActive());
        assertEquals(0, state.runtime(AlertType.OVER_VOLTAGE).getStreak());
    }
}
