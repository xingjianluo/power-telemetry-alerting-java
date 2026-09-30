package com.github.xingjianluo.powertelemetryalertingjava.model;

/**
 * 单台设备的阈值与告警配置（可空字段表示沿用全局默认）。
 * 由管理接口按设备单独调整。
 */
public class DeviceConfig {
    private Double overVoltageV;
    private Double underVoltageV;
    private Double overCurrentA;
    private Double overPowerW;
    private Long interruptionTimeoutMs;
    private Integer breachTimes;
    private Integer recoverTimes;
    private Boolean alertsEnabled;

    public Double getOverVoltageV() { return overVoltageV; }
    public void setOverVoltageV(Double overVoltageV) { this.overVoltageV = overVoltageV; }
    public Double getUnderVoltageV() { return underVoltageV; }
    public void setUnderVoltageV(Double underVoltageV) { this.underVoltageV = underVoltageV; }
    public Double getOverCurrentA() { return overCurrentA; }
    public void setOverCurrentA(Double overCurrentA) { this.overCurrentA = overCurrentA; }
    public Double getOverPowerW() { return overPowerW; }
    public void setOverPowerW(Double overPowerW) { this.overPowerW = overPowerW; }
    public Long getInterruptionTimeoutMs() { return interruptionTimeoutMs; }
    public void setInterruptionTimeoutMs(Long interruptionTimeoutMs) { this.interruptionTimeoutMs = interruptionTimeoutMs; }
    public Integer getBreachTimes() { return breachTimes; }
    public void setBreachTimes(Integer breachTimes) { this.breachTimes = breachTimes; }
    public Integer getRecoverTimes() { return recoverTimes; }
    public void setRecoverTimes(Integer recoverTimes) { this.recoverTimes = recoverTimes; }
    public Boolean getAlertsEnabled() { return alertsEnabled; }
    public void setAlertsEnabled(Boolean alertsEnabled) { this.alertsEnabled = alertsEnabled; }
}
