package com.github.xingjianluo.powertelemetryalertingjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个固定时间窗口内的聚合结果。
 * 窗口为 epoch 对齐：startMs 为 epoch/windowMs*windowMs，包含 startMs，不包含 endMs。
 */
public class WindowData {
    private long startMs;
    private long endMs;
    private int count;
    private double avgPowerW;
    private double peakPowerW;
    private double energyWh;
    private List<Long> sampleTimestamps = new ArrayList<>();

    public long getStartMs() { return startMs; }
    public void setStartMs(long startMs) { this.startMs = startMs; }
    public long getEndMs() { return endMs; }
    public void setEndMs(long endMs) { this.endMs = endMs; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
    public double getAvgPowerW() { return avgPowerW; }
    public void setAvgPowerW(double avgPowerW) { this.avgPowerW = avgPowerW; }
    public double getPeakPowerW() { return peakPowerW; }
    public void setPeakPowerW(double peakPowerW) { this.peakPowerW = peakPowerW; }
    public double getEnergyWh() { return energyWh; }
    public void setEnergyWh(double energyWh) { this.energyWh = energyWh; }
    public List<Long> getSampleTimestamps() { return sampleTimestamps; }
    public void setSampleTimestamps(List<Long> sampleTimestamps) { this.sampleTimestamps = sampleTimestamps; }
}
