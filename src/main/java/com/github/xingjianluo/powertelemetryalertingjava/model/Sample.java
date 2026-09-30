package com.github.xingjianluo.powertelemetryalertingjava.model;

/** 校验通过后的规范化采样点，按 (设备, timestampMs) 唯一。 */
public final class Sample {
    private final long timestampMs;
    private final double voltageV;
    private final double currentA;
    private final double powerW;

    public Sample(long timestampMs, double voltageV, double currentA, double powerW) {
        this.timestampMs = timestampMs;
        this.voltageV = voltageV;
        this.currentA = currentA;
        this.powerW = powerW;
    }

    public long getTimestampMs() { return timestampMs; }
    public double getVoltageV() { return voltageV; }
    public double getCurrentA() { return currentA; }
    public double getPowerW() { return powerW; }

    public boolean sameReading(Sample other) {
        return timestampMs == other.timestampMs
                && voltageV == other.voltageV
                && currentA == other.currentA
                && powerW == other.powerW;
    }
}
