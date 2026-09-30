package com.github.xingjianluo.powertelemetryalertingjava.model;

/** 上报接口接收到的原始记录（字段可缺失，用于区分拒绝原因）。 */
public class RawSample {
    private Long timestampMs;
    private Double voltageV;
    private Double currentA;
    private Double powerW;

    public Long getTimestampMs() { return timestampMs; }
    public void setTimestampMs(Long timestampMs) { this.timestampMs = timestampMs; }
    public Double getVoltageV() { return voltageV; }
    public void setVoltageV(Double voltageV) { this.voltageV = voltageV; }
    public Double getCurrentA() { return currentA; }
    public Double getPowerW() { return powerW; }
    public void setCurrentA(Double currentA) { this.currentA = currentA; }
    public void setPowerW(Double powerW) { this.powerW = powerW; }
}
