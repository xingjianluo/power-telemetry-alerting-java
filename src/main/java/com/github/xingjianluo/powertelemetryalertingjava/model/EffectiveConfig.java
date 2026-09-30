package com.github.xingjianluo.powertelemetryalertingjava.model;

import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;

/**
 * 全局默认与设备覆盖合并后的最终生效配置（不可变）。
 * 合并在每台设备的锁内完成，非法配置抛 IllegalArgumentException 且不会影响已有状态。
 */
public record EffectiveConfig(
        double overVoltageV,
        double underVoltageV,
        double overCurrentA,
        double overPowerW,
        long interruptionTimeoutMs,
        int breachTimes,
        int recoverTimes,
        boolean alertsEnabled) {

    /** null 覆盖表示完全使用默认值。 */
    public static EffectiveConfig merge(ConfigProperties props, DeviceConfig override) {
        double overV = override != null && override.getOverVoltageV() != null
                ? override.getOverVoltageV() : props.getDefaultOverVoltageV();
        double underV = override != null && override.getUnderVoltageV() != null
                ? override.getUnderVoltageV() : props.getDefaultUnderVoltageV();
        double overA = override != null && override.getOverCurrentA() != null
                ? override.getOverCurrentA() : props.getDefaultOverCurrentA();
        double overW = override != null && override.getOverPowerW() != null
                ? override.getOverPowerW() : props.getDefaultOverPowerW();
        long timeout = override != null && override.getInterruptionTimeoutMs() != null
                ? override.getInterruptionTimeoutMs() : props.getDefaultInterruptionTimeoutMs();
        int breach = override != null && override.getBreachTimes() != null
                ? override.getBreachTimes() : props.getDefaultBreachTimes();
        int recover = override != null && override.getRecoverTimes() != null
                ? override.getRecoverTimes() : props.getDefaultRecoverTimes();
        boolean enabled = override == null || override.getAlertsEnabled() == null
                || override.getAlertsEnabled();
        return new EffectiveConfig(overV, underV, overA, overW, timeout, breach, recover, enabled);
    }

    /** 校验合并后的配置；非法消息明确指出字段与原因。 */
    public void validate() {
        if (Double.isNaN(overVoltageV) || Double.isInfinite(overVoltageV) || overVoltageV <= 0) {
            throw new IllegalArgumentException("overVoltageV must be a positive number: " + overVoltageV);
        }
        if (Double.isNaN(underVoltageV) || Double.isInfinite(underVoltageV) || underVoltageV <= 0) {
            throw new IllegalArgumentException("underVoltageV must be a positive number: " + underVoltageV);
        }
        if (underVoltageV >= overVoltageV) {
            throw new IllegalArgumentException(
                    "underVoltageV (" + underVoltageV + ") must be < overVoltageV (" + overVoltageV + ")");
        }
        if (Double.isNaN(overCurrentA) || Double.isInfinite(overCurrentA) || overCurrentA <= 0) {
            throw new IllegalArgumentException("overCurrentA must be a positive number: " + overCurrentA);
        }
        if (Double.isNaN(overPowerW) || Double.isInfinite(overPowerW) || overPowerW <= 0) {
            throw new IllegalArgumentException("overPowerW must be a positive number: " + overPowerW);
        }
        if (interruptionTimeoutMs <= 0) {
            throw new IllegalArgumentException("interruptionTimeoutMs must be positive: " + interruptionTimeoutMs);
        }
        if (breachTimes <= 0) {
            throw new IllegalArgumentException("breachTimes must be >= 1: " + breachTimes);
        }
        if (recoverTimes <= 0) {
            throw new IllegalArgumentException("recoverTimes must be >= 1: " + recoverTimes);
        }
    }
}
