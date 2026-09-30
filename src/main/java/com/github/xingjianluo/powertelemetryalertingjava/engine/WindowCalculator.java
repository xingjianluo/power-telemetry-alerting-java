package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.model.Sample;
import com.github.xingjianluo.powertelemetryalertingjava.model.WindowData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基于保留窗口内的全部采样点重算窗口聚合（乱序安全：插入迟到点后整体重算，结果幂等）。
 *
 * <p>能耗采用相邻采样点间梯形积分：energy(Wh) = (P_prev + P)/2 * dt_ms / 3_600_000。
 * 跨窗口边界的线段按边界切分，每一段归属其起始样本所在窗口；
 * 因此窗口边界无论何时重算都是确定的。
 */
@Component
public class WindowCalculator {

    private static final Logger log = LoggerFactory.getLogger(WindowCalculator.class);

    private static final double MS_PER_HOUR = 3_600_000_000.0d;

    /**
     * 重算 startMs &gt;= horizonMs 的窗口。
     *
     * @param windows      已有窗口（会被原地更新/删除）
     * @param allSamples   全部保留采样点（按时间排序）
     * @param windowMs     窗口长度
     * @param horizonMs    保留下界（窗口起点 &lt; 该值的被淘汰）
     * @param prevSampleMs 提示用；实际前驱从 allSamples 自行定位
     */
    public void recompute(TreeMap<Long, WindowData> windows, TreeMap<Long, Sample> allSamples,
                          long windowMs, long horizonMs, Long prevSampleMs) {
        long horizonStart = alignFloor(horizonMs, windowMs);

        // 1) 淘汰超出保留范围的窗口。
        windows.headMap(horizonStart, false).clear();

        // 2) 收集落在保留窗口内的采样点，并找到第一个保留采样点的前驱（用于跨边界能耗）。
        Map.Entry<Long, Sample> firstEntry = allSamples.ceilingEntry(horizonStart);
        if (firstEntry == null) {
            return;
        }
        Sample predecessor = allSamples.lowerEntry(firstEntry.getKey()) != null
                ? allSamples.lowerEntry(firstEntry.getKey()).getValue() : null;

        // 3) 每个窗口初始化聚合累加器。
        TreeMap<Long, Acc> accMap = new TreeMap<>();

        Sample prev = predecessor;
        for (Sample s : allSamples.tailMap(firstEntry.getKey(), true).values()) {
            long wStart = alignFloor(s.getTimestampMs(), windowMs);
            Acc acc = accMap.computeIfAbsent(wStart, k -> new Acc(k, k + windowMs));
            acc.count++;
            acc.sumPower += s.getPowerW();
            acc.peakPower = Math.max(acc.peakPower, s.getPowerW());
            acc.timestamps.add(s.getTimestampMs());

            if (prev != null && s.getTimestampMs() > prev.getTimestampMs()) {
                long segStartWindow = alignFloor(prev.getTimestampMs(), windowMs);
                if (segStartWindow >= horizonStart) {
                    addSegmentEnergy(accMap, prev, s, windowMs, horizonStart);
                }
            }
            prev = s;
        }

        // 4) 用累加结果替换窗口（保留窗口集合中本次无采样点的旧窗口也清掉）。
        windows.tailMap(horizonStart, true).keySet().retainAll(accMap.keySet());
        for (Acc acc : accMap.values()) {
            WindowData wd = windows.computeIfAbsent(acc.start, k -> {
                WindowData d = new WindowData();
                d.setStartMs(acc.start);
                d.setEndMs(acc.end);
                log.debug("window opened: start={} end={}", acc.start, acc.end);
                return d;
            });
            wd.setStartMs(acc.start);
            wd.setEndMs(acc.end);
            wd.setCount(acc.count);
            wd.setAvgPowerW(acc.count == 0 ? 0.0d : acc.sumPower / acc.count);
            wd.setPeakPowerW(acc.peakPower);
            wd.setEnergyWh(acc.energyWh);
            wd.setSampleTimestamps(acc.timestamps);
        }
    }

    /** 将 prev->cur 的能耗线段按窗口边界切分后累加（Wh）。 */
    private void addSegmentEnergy(TreeMap<Long, Acc> accMap, Sample prev, Sample cur,
                                  long windowMs, long horizonStart) {
        long t0 = prev.getTimestampMs();
        long t1 = cur.getTimestampMs();
        double p0 = prev.getPowerW();
        double p1 = cur.getPowerW();
        long segStart = t0;
        long segStartWindow = alignFloor(segStart, windowMs);
        long boundary = segStartWindow + windowMs;
        while (boundary < t1) {
            double wh = trapezoidWh(p0, p1, t0, t1, segStart, boundary);
            accumulate(accMap, segStartWindow, wh, windowMs, horizonStart);
            segStart = boundary;
            segStartWindow = boundary;
            boundary += windowMs;
        }
        double wh = trapezoidWh(p0, p1, t0, t1, segStart, t1);
        accumulate(accMap, segStartWindow, wh, windowMs, horizonStart);
    }

    /**
     * 线性功率下 [a,b) 子段的能耗（时间全部使用毫秒）：
     * P(t) 在 (t0,t1) 上线性，P(a)=p0+(p1-p0)*(a-t0)/(t1-t0)，段内取平均。
     */
    private static double trapezoidWh(double p0, double p1, long t0, long t1, long a, long b) {
        double total = t1 - t0;
        double pa = p0 + (p1 - p0) * ((double) (a - t0) / total);
        double pb = p0 + (p1 - p0) * ((double) (b - t0) / total);
        return (pa + pb) / 2.0d * (b - a) / MS_PER_HOUR;
    }

    private void accumulate(TreeMap<Long, Acc> accMap, long windowStart, double wh,
                            long windowMs, long horizonStart) {
        if (windowStart < horizonStart) {
            return;
        }
        Acc acc = accMap.computeIfAbsent(windowStart, k -> new Acc(k, k + windowMs));
        acc.energyWh += wh;
    }

    /** epoch 对齐到窗口起点（floor 语义，兼容负时间戳）。 */
    public static long alignFloor(long ts, long windowMs) {
        return Math.floorDiv(ts, windowMs) * windowMs;
    }

    public static List<WindowData> ordered(TreeMap<Long, WindowData> windows) {
        return new ArrayList<>(windows.values());
    }

    private static final class Acc {
        final long start;
        final long end;
        int count;
        double sumPower;
        double peakPower = Double.NEGATIVE_INFINITY;
        double energyWh;
        final List<Long> timestamps = new ArrayList<>();

        Acc(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }
}
