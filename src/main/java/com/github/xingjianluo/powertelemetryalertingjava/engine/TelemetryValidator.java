package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import com.github.xingjianluo.powertelemetryalertingjava.model.RawSample;
import com.github.xingjianluo.powertelemetryalertingjava.model.RejectReason;
import com.github.xingjianluo.powertelemetryalertingjava.model.Sample;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 单条记录的字段与时间范围校验。
 *
 * <p>拒绝原因严格区分：
 * <ul>
 *   <li>MISSING_*：关键字段缺失；</li>
 *   <li>INVALID_VALUE：字段存在但非有限数或电压/电流为负；</li>
 *   <li>FUTURE_TIMESTAMP：超过 now + futureSkewMs；</li>
 *   <li>TOO_OLD：早于 now - maxAgeMs（允许时间范围之外）；</li>
 *   <li>TIME_ROLLBACK：在允许范围内，但比设备最新已接受点回退超过 reorderLagMs（明显回退）。</li>
 * </ul>
 */
@Component
public class TelemetryValidator {

    private final ConfigProperties props;
    private final Clock clock;

    public TelemetryValidator(ConfigProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /** 校验结果：合法时 sample 非空；非法时 reason 非空。 */
    public record Outcome(Sample sample, RejectReason reason, String message) {
        public static Outcome ok(Sample s) { return new Outcome(s, null, null); }
        public static Outcome reject(RejectReason r, String m) { return new Outcome(null, r, m); }
    }

    public Outcome validate(RawSample raw, long newestAcceptedMs) {
        if (raw == null) {
            return Outcome.reject(RejectReason.INVALID_VALUE, "record is null");
        }
        if (raw.getTimestampMs() == null) {
            return Outcome.reject(RejectReason.MISSING_TIMESTAMP, "timestampMs is required");
        }
        long ts = raw.getTimestampMs();
        if (raw.getVoltageV() == null) {
            return Outcome.reject(RejectReason.MISSING_VOLTAGE,
                    "voltageV is required, timestampMs=" + ts);
        }
        if (raw.getCurrentA() == null) {
            return Outcome.reject(RejectReason.MISSING_CURRENT,
                    "currentA is required, timestampMs=" + ts);
        }
        if (raw.getPowerW() == null) {
            return Outcome.reject(RejectReason.MISSING_POWER,
                    "powerW is required, timestampMs=" + ts);
        }
        double v = raw.getVoltageV();
        double a = raw.getCurrentA();
        double w = raw.getPowerW();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return Outcome.reject(RejectReason.INVALID_VALUE,
                    "voltageV must be a finite number, timestampMs=" + ts);
        }
        if (Double.isNaN(a) || Double.isInfinite(a)) {
            return Outcome.reject(RejectReason.INVALID_VALUE,
                    "currentA must be a finite number, timestampMs=" + ts);
        }
        if (Double.isNaN(w) || Double.isInfinite(w)) {
            return Outcome.reject(RejectReason.INVALID_VALUE,
                    "powerW must be a finite number, timestampMs=" + ts);
        }
        if (v < 0.0d) {
            return Outcome.reject(RejectReason.INVALID_VALUE,
                    "voltageV must not be negative: " + v + ", timestampMs=" + ts);
        }
        if (a < 0.0d) {
            return Outcome.reject(RejectReason.INVALID_VALUE,
                    "currentA must not be negative: " + a + ", timestampMs=" + ts);
        }

        long now = clock.millis();
        if (ts > now + props.getFutureSkewMs()) {
            return Outcome.reject(RejectReason.FUTURE_TIMESTAMP,
                    "timestampMs " + ts + " is more than " + props.getFutureSkewMs()
                            + "ms in the future (now=" + now + ")");
        }
        if (ts < now - props.getMaxAgeMs()) {
            return Outcome.reject(RejectReason.TOO_OLD,
                    "timestampMs " + ts + " is older than maxAge " + props.getMaxAgeMs()
                            + "ms (now=" + now + ")");
        }
        if (newestAcceptedMs != Long.MIN_VALUE
                && ts < newestAcceptedMs - props.getReorderLagMs()) {
            return Outcome.reject(RejectReason.TIME_ROLLBACK,
                    "timestampMs " + ts + " rolls back more than reorderLag "
                            + props.getReorderLagMs() + "ms from newest accepted "
                            + newestAcceptedMs);
        }
        return Outcome.ok(new Sample(ts, v, a, w));
    }
}
