package com.github.xingjianluo.powertelemetryalertingjava.tests.engine;

import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.TelemetryValidator;
import com.github.xingjianluo.powertelemetryalertingjava.model.RawSample;
import com.github.xingjianluo.powertelemetryalertingjava.model.RejectReason;
import com.github.xingjianluo.powertelemetryalertingjava.tests.support.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 拒绝原因必须逐字段可区分，合法记录正常接受，允许范围内乱序不拒绝。 */
class TelemetryValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryValidatorTest.class);

    private static final long T0 = 1_000_000_000_000L;
    private ManualClock clock;
    private ConfigProperties props;
    private TelemetryValidator validator;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        props = new ConfigProperties();
        props.validate();
        validator = new TelemetryValidator(props, clock);
    }

    private RawSample raw(Long ts, Double v, Double a, Double w) {
        RawSample r = new RawSample();
        r.setTimestampMs(ts);
        r.setVoltageV(v);
        r.setCurrentA(a);
        r.setPowerW(w);
        return r;
    }

    private void expectReject(RawSample r, long newest, RejectReason reason) {
        TelemetryValidator.Outcome o = validator.validate(r, newest);
        assertNull(o.sample());
        assertEquals(reason, o.reason(), "message=" + o.message());
        log.info("reject reason={} msg='{}'", o.reason(), o.message());
    }

    @Test
    @DisplayName("各字段缺失分别给出不同拒绝原因")
    void missingFieldsAreDistinguished() {
        expectReject(raw(null, 220.0, 5.0, 1000.0), Long.MIN_VALUE, RejectReason.MISSING_TIMESTAMP);
        expectReject(raw(T0, null, 5.0, 1000.0), Long.MIN_VALUE, RejectReason.MISSING_VOLTAGE);
        expectReject(raw(T0, 220.0, null, 1000.0), Long.MIN_VALUE, RejectReason.MISSING_CURRENT);
        expectReject(raw(T0, 220.0, 5.0, null), Long.MIN_VALUE, RejectReason.MISSING_POWER);
    }

    @Test
    @DisplayName("非有限数与负电压/负电流判为 INVALID_VALUE；负功率允许（可能为馈网）")
    void invalidValuesRejected() {
        expectReject(raw(T0, Double.NaN, 5.0, 1000.0), Long.MIN_VALUE, RejectReason.INVALID_VALUE);
        expectReject(raw(T0, 220.0, Double.POSITIVE_INFINITY, 1000.0), Long.MIN_VALUE,
                RejectReason.INVALID_VALUE);
        expectReject(raw(T0, -1.0, 5.0, 1000.0), Long.MIN_VALUE, RejectReason.INVALID_VALUE);
        expectReject(raw(T0, 220.0, -0.1, 1000.0), Long.MIN_VALUE, RejectReason.INVALID_VALUE);
        // 负功率合法
        TelemetryValidator.Outcome ok = validator.validate(raw(T0, 220.0, 5.0, -10.0), Long.MIN_VALUE);
        assertNotNull(ok.sample());
    }

    @Test
    @DisplayName("超过 futureSkew 的未来时间被拒绝")
    void futureTimestampRejected() {
        expectReject(raw(T0 + props.getFutureSkewMs() + 1, 220.0, 5.0, 1000.0), Long.MIN_VALUE,
                RejectReason.FUTURE_TIMESTAMP);
        // 偏差边界内合法
        assertNotNull(validator.validate(raw(T0 + props.getFutureSkewMs(), 220.0, 5.0, 1000.0),
                Long.MIN_VALUE).sample());
    }

    @Test
    @DisplayName("超出 maxAge 的历史数据被拒绝为 TOO_OLD")
    void tooOldRejected() {
        expectReject(raw(T0 - props.getMaxAgeMs() - 1, 220.0, 5.0, 1000.0), Long.MIN_VALUE,
                RejectReason.TOO_OLD);
    }

    @Test
    @DisplayName("比设备最新点回退超过 reorderLag 视为明显时间回退；范围内乱序允许")
    void rollbackRejectedButReorderAllowed() {
        long newest = T0;
        // 回退 600_001ms：拒绝
        expectReject(raw(newest - props.getReorderLagMs() - 1, 220.0, 5.0, 1000.0), newest,
                RejectReason.TIME_ROLLBACK);
        // 回退 600_000ms（边界）：允许，属于乱序归位
        TelemetryValidator.Outcome reordered = validator.validate(
                raw(newest - props.getReorderLagMs(), 220.0, 5.0, 1000.0), newest);
        assertNotNull(reordered.sample());
        // 设备尚无数据时不做回退判定
        assertNotNull(validator.validate(raw(T0 - 10_000_000L, 220.0, 5.0, 1000.0),
                Long.MIN_VALUE).sample());
    }

    @Test
    @DisplayName("合法记录接受且数值透传")
    void validRecordAccepted() {
        TelemetryValidator.Outcome o = validator.validate(raw(T0, 220.5, 5.25, 1100.0), Long.MIN_VALUE);
        assertNotNull(o.sample());
        assertNull(o.reason());
        assertEquals(T0, o.sample().getTimestampMs());
        assertEquals(220.5, o.sample().getVoltageV(), 0.0d);
        assertEquals(5.25, o.sample().getCurrentA(), 0.0d);
        assertEquals(1100.0, o.sample().getPowerW(), 0.0d);
    }
}
