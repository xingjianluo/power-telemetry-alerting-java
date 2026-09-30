package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.xingjianluo.powertelemetryalertingjava.model.TelemetrySample;
import com.github.xingjianluo.powertelemetryalertingjava.model.WindowStats;

import java.util.Map;
import java.util.TreeMap;

/**
 * 单个滚动窗口（[start, start+length)，左闭右开）内的统计累加器。
 *
 * <p>乱序点可在窗口封存前任意插入，统计每次基于窗口内有序采样重算，
 * 因而结果只与采样点集合有关、与到达顺序无关（可复现）。</p>
 *
 * <p>能耗采用相邻采样间梯形面积积分（W·ms → Wh），平均功率 = 能量 / 覆盖时长，
 * 单点窗口退化为该点功率。</p>
 */
public final class WindowAccumulator {

    private final long windowStart;
    private final long windowLength;
    private final TreeMap<Long, TelemetrySample> samples = new TreeMap<>();
    private boolean closed;

    @JsonCreator
    public WindowAccumulator(@JsonProperty("windowStart") long windowStart,
                             @JsonProperty("windowLength") long windowLength) {
        this.windowStart = windowStart;
        this.windowLength = windowLength;
    }

    public long getWindowStart() { return windowStart; }
    public long getWindowLength() { return windowLength; }
    public TreeMap<Long, TelemetrySample> getSamples() { return samples; }
    public boolean isClosed() { return closed; }
    public void setClosed(boolean closed) { this.closed = closed; }

    /** 放入一条采样；同采样时间覆盖返回 false，新增返回 true。去重判定由调用方先做。 */
    public boolean accept(TelemetrySample sample) {
        return samples.put(sample.sampleTime(), sample) == null;
    }

    /**
     * 重算窗口统计。窗口内按时间有序：
     * 能量 = Σ (P_i + P_{i+1})/2 × Δt / 3_600_000  (Wh)
     */
    public WindowStats toStats() {
        int n = samples.size();
        if (n == 0) {
            return new WindowStats(windowStart, windowLength, 0, 0d, 0d, 0d, closed);
        }
        double peak = Double.NEGATIVE_INFINITY;
        double energyWms = 0d;
        long firstTime = samples.firstKey();
        long lastTime = firstTime;
        TelemetrySample prev = null;
        for (Map.Entry<Long, TelemetrySample> e : samples.entrySet()) {
            TelemetrySample s = e.getValue();
            peak = Math.max(peak, s.power());
            if (prev != null) {
                double dt = s.sampleTime() - prev.sampleTime();
                energyWms += (prev.power() + s.power()) / 2.0 * dt;
            }
            prev = s;
            lastTime = s.sampleTime();
        }
        double coveredMs = Math.max(1d, lastTime - firstTime);
        double energyWh = energyWms / 3_600_000.0d;
        // 单点时平均功率即为该点功率；多点时按实际采样跨度归一
        double avgPower = n == 1 ? prev.power() : energyWms / coveredMs;
        return new WindowStats(windowStart, windowLength, n, avgPower, peak, energyWh, closed);
    }
}
