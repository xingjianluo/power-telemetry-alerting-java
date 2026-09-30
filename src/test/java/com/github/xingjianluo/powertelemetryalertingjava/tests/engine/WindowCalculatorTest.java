package com.github.xingjianluo.powertelemetryalertingjava.tests.engine;

import com.github.xingjianluo.powertelemetryalertingjava.engine.WindowCalculator;
import com.github.xingjianluo.powertelemetryalertingjava.model.Sample;
import com.github.xingjianluo.powertelemetryalertingjava.model.WindowData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 窗口聚合：平均值、峰值、梯形积分能耗（Wh）、跨边界切分与乱序重算幂等。
 * 所有时间戳单位为毫秒：1 Wh = 3_600_000_000 W·ms。
 */
class WindowCalculatorTest {

    private static final Logger log = LoggerFactory.getLogger(WindowCalculatorTest.class);
    private static final long W = 60_000L;
    private static final double WMS_PER_WH = 3_600_000_000.0d;

    private final WindowCalculator calc = new WindowCalculator();

    private Sample sample(long ts, double w) {
        return new Sample(ts, 220.0, 5.0, w);
    }

    @Test
    @DisplayName("单窗口：平均功率=算术均值，峰值=最大功率，能耗=相邻样本梯形积分")
    void aggregatesSingleWindow() {
        TreeMap<Long, Sample> samples = new TreeMap<>();
        samples.put(1_000L, sample(1_000L, 100));
        samples.put(2_000L, sample(2_000L, 200));
        samples.put(3_000L, sample(3_000L, 300));
        TreeMap<Long, WindowData> windows = new TreeMap<>();

        calc.recompute(windows, samples, W, 0L, null);

        assertEquals(1, windows.size());
        WindowData w0 = windows.get(0L);
        assertEquals(0L, w0.getStartMs());
        assertEquals(W, w0.getEndMs());
        assertEquals(3, w0.getCount());
        assertEquals(200.0d, w0.getAvgPowerW(), 1e-9);
        assertEquals(300.0d, w0.getPeakPowerW(), 0.0d);
        // (150W*1000ms + 250W*1000ms) = 400_000 W·ms
        assertEquals(400_000.0d / WMS_PER_WH, w0.getEnergyWh(), 1e-15);
        log.info("[window 0..60000] count={} avgW={} peakW={} energyWh={} samples={}",
                w0.getCount(), w0.getAvgPowerW(), w0.getPeakPowerW(), w0.getEnergyWh(),
                w0.getSampleTimestamps());
    }

    @Test
    @DisplayName("跨窗口边界：能耗按边界切分，片段归属起始样本所在窗口")
    void energySplitsAtWindowBoundary() {
        TreeMap<Long, Sample> samples = new TreeMap<>();
        samples.put(50_000L, sample(50_000L, 100));
        samples.put(70_000L, sample(70_000L, 100));
        TreeMap<Long, WindowData> windows = new TreeMap<>();

        calc.recompute(windows, samples, W, 0L, null);

        assertEquals(2, windows.size());
        // 50s->60s：100W * 10_000ms = 1_000_000 W·ms
        assertEquals(1_000_000.0d / WMS_PER_WH, windows.get(0L).getEnergyWh(), 1e-15);
        // 60s->70s：同上
        assertEquals(1_000_000.0d / WMS_PER_WH, windows.get(W).getEnergyWh(), 1e-15);
        log.info("[boundary split] window0 energyWh={} window1 energyWh={}",
                windows.get(0L).getEnergyWh(), windows.get(W).getEnergyWh());
    }

    @Test
    @DisplayName("乱序插入迟到点后整体重算，结果与顺序到达完全一致（幂等）")
    void lateArrivalRecomputeIsIdempotent() {
        TreeMap<Long, Sample> ordered = new TreeMap<>();
        ordered.put(0L, sample(0L, 100));
        ordered.put(30_000L, sample(30_000L, 400));
        ordered.put(60_000L, sample(60_000L, 200));
        ordered.put(90_000L, sample(90_000L, 300));
        TreeMap<Long, WindowData> expected = new TreeMap<>();
        calc.recompute(expected, ordered, W, 0L, null);

        // 模拟先到中间点，再到更早的迟到点，再到更晚的点
        TreeMap<Long, Sample> jumbled = new TreeMap<>();
        TreeMap<Long, WindowData> actual = new TreeMap<>();
        jumbled.put(60_000L, sample(60_000L, 200));
        calc.recompute(actual, jumbled, W, 0L, null);
        jumbled.put(30_000L, sample(30_000L, 400));
        calc.recompute(actual, jumbled, W, 0L, null);
        jumbled.put(90_000L, sample(90_000L, 300));
        calc.recompute(actual, jumbled, W, 0L, null);
        jumbled.put(0L, sample(0L, 100));
        calc.recompute(actual, jumbled, W, 0L, null);

        assertEquals(expected.keySet(), actual.keySet());
        for (Long start : expected.keySet()) {
            WindowData e = expected.get(start);
            WindowData a = actual.get(start);
            assertEquals(e.getCount(), a.getCount(), "count mismatch at window " + start);
            assertEquals(e.getAvgPowerW(), a.getAvgPowerW(), 1e-9, "avg mismatch at window " + start);
            assertEquals(e.getPeakPowerW(), a.getPeakPowerW(), 0.0d, "peak mismatch at window " + start);
            assertEquals(e.getEnergyWh(), a.getEnergyWh(), 1e-15, "energy mismatch at window " + start);
        }
        log.info("[late recompute] windows={}", actual.keySet());
    }

    @Test
    @DisplayName("低于保留界的旧窗口被淘汰，界外前驱只贡献界内片段")
    void evictsWindowsBelowHorizon() {
        TreeMap<Long, Sample> samples = new TreeMap<>();
        samples.put(-10_000L, sample(-10_000L, 100));
        samples.put(5_000L, sample(5_000L, 100));
        samples.put(W + 5_000L, sample(W + 5_000L, 100));
        TreeMap<Long, WindowData> windows = new TreeMap<>();

        calc.recompute(windows, samples, W, 0L, null);

        // -10s 属于 -60000 窗口（已淘汰），-10s->5s 线段起点在界外被整体跳过；
        // 5s->65s 跨 0/60000 边界：5s..60s（55_000ms*100W）归窗口0，60s..65s 归窗口1
        assertTrue(windows.containsKey(0L));
        assertTrue(windows.containsKey(W));
        assertEquals(5_500_000.0d / WMS_PER_WH, windows.get(0L).getEnergyWh(), 1e-15);
        assertEquals(500_000.0d / WMS_PER_WH, windows.get(W).getEnergyWh(), 1e-15);
        assertEquals(1, windows.get(0L).getCount());
    }

    @Test
    @DisplayName("线性爬升功率的跨窗口能耗：分段线性积分与解析值一致")
    void linearRampAcrossBoundary() {
        TreeMap<Long, Sample> samples = new TreeMap<>();
        samples.put(0L, sample(0L, 0));
        samples.put(120_000L, sample(120_000L, 120));
        TreeMap<Long, WindowData> windows = new TreeMap<>();

        calc.recompute(windows, samples, W, 0L, null);

        // P(t) 以秒计：P=t_s (W)，窗口0 [0,60s)：积分 = 0.5*60*60 Ws = 1_800 Ws = 1_800_000 W·ms
        assertEquals(1_800_000.0d / WMS_PER_WH, windows.get(0L).getEnergyWh(), 1e-12);
        // 窗口1 [60s,120s)：积分 = 0.5*(60+120)*60 = 5_400 Ws = 5_400_000 W·ms
        assertEquals(5_400_000.0d / WMS_PER_WH, windows.get(W).getEnergyWh(), 1e-12);
        // 终点采样点 120000 落在窗口2（该窗口只有点、没有能耗段）
        assertEquals(1, windows.get(2 * W).getCount());
        assertEquals(0.0d, windows.get(2 * W).getEnergyWh(), 0.0d);
        List<WindowData> orderedWindows = WindowCalculator.ordered(windows);
        assertEquals(3, orderedWindows.size());
    }
}
