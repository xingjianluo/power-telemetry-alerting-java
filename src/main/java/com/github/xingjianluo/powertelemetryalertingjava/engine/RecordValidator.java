package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.model.RejectCode;
import com.github.xingjianluo.powertelemetryalertingjava.model.TelemetrySample;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * 对单条遥测记录做字段、数值、时间范围、回退、重复校验。
 *
 * <p>判定顺序固定，保证拒绝原因唯一且可区分：</p>
 * <ol>
 *   <li>{@code MISSING_FIELD}：设备号/采样时间/电压/电流/功率为 null；</li>
 *   <li>{@code INVALID_VALUE}：NaN、无穷、电压/电流/功率为负；</li>
 *   <li>{@code FUTURE_TIMESTAMP}：采样时间晚于 now + 未来偏差；</li>
 *   <li>{@code OUT_OF_RANGE}：采样时间早于 now - 最大历史范围；</li>
 *   <li>{@code DUPLICATE}：同设备同采样时间已存在；</li>
 *   <li>{@code TIME_ROLLBACK}：早于该设备水位线超过允许乱序迟到窗口。</li>
 * </ol>
 */
@Component
public class RecordValidator {

    private final LongSupplier clock;

    public RecordValidator(LongSupplier clock) {
        this.clock = clock;
    }

    /** 字段存在性 + 数值合法性校验。 */
    public ValidationOutcome validateFields(String deviceId, Long timestamp,
                                            Double voltage, Double current, Double power) {
        if (deviceId == null || deviceId.isBlank()) {
            return ValidationOutcome.reject(RejectCode.MISSING_FIELD, "设备编号缺失");
        }
        if (timestamp == null) {
            return ValidationOutcome.reject(RejectCode.MISSING_FIELD, "采样时间 timestamp 缺失");
        }
        if (voltage == null) {
            return ValidationOutcome.reject(RejectCode.MISSING_FIELD, "电压 voltage 缺失");
        }
        if (current == null) {
            return ValidationOutcome.reject(RejectCode.MISSING_FIELD, "电流 current 缺失");
        }
        if (power == null) {
            return ValidationOutcome.reject(RejectCode.MISSING_FIELD, "功率 power 缺失");
        }
        if (!Double.isFinite(voltage) || voltage < 0d) {
            return ValidationOutcome.reject(RejectCode.INVALID_VALUE,
                    "电压非法: " + voltage + " V，要求有限且非负");
        }
        if (!Double.isFinite(current) || current < 0d) {
            return ValidationOutcome.reject(RejectCode.INVALID_VALUE,
                    "电流非法: " + current + " A，要求有限且非负");
        }
        if (!Double.isFinite(power) || power < 0d) {
            return ValidationOutcome.reject(RejectCode.INVALID_VALUE,
                    "功率非法: " + power + " W，要求有限且非负");
        }
        return ValidationOutcome.ok();
    }

    /**
     * 时间语义校验（调用方须持有设备锁）。
     */
    public ValidationOutcome validateTime(TelemetrySample sample, DeviceState state,
                                          long maxHistoryMs, long futureToleranceMs,
                                          long allowedLatenessMs) {
        long now = clock.getAsLong();
        long ts = sample.sampleTime();

        if (ts > now + futureToleranceMs) {
            return ValidationOutcome.reject(RejectCode.FUTURE_TIMESTAMP,
                    String.format("采样时间 %d 超出当前时间 %d 与未来容忍 %dms", ts, now, futureToleranceMs));
        }
        long historyFloor = now - maxHistoryMs;
        if (ts < historyFloor) {
            return ValidationOutcome.reject(RejectCode.OUT_OF_RANGE,
                    String.format("采样时间 %d 早于允许历史下限 %d（最大范围 %dms）", ts, historyFloor, maxHistoryMs));
        }
        if (state.getSamples().containsKey(ts)) {
            return ValidationOutcome.reject(RejectCode.DUPLICATE,
                    "设备 " + sample.deviceId() + " 在采样时间 " + ts + " 的数据已存在，不重复累计");
        }
        long wm = state.getWatermark();
        if (wm != Long.MIN_VALUE && ts < wm - allowedLatenessMs) {
            return ValidationOutcome.reject(RejectCode.TIME_ROLLBACK,
                    String.format("采样时间 %d 相对水位线 %d 回退超过允许迟到 %dms", ts, wm, allowedLatenessMs));
        }
        return ValidationOutcome.ok();
    }
}
