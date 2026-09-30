package com.github.xingjianluo.powertelemetryalertingjava.tests;

import com.github.xingjianluo.powertelemetryalertingjava.engine.WindowAccumulator;
import com.github.xingjianluo.powertelemetryalertingjava.model.TelemetrySample;
import com.github.xingjianluo.powertelemetryalertingjava.model.WindowStats;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 窗口统计：梯形积分、乱序插入结果与顺序无关、窗口封存。
 */
class WindowAccumulatorTest {

    private static final Logger log = LoggerFactory.getLogger(WindowAccumulatorTest.class);
    private static final long T0 = 1_000_000L;
    private static final long W = 60_000L;

    private TelemetrySample s(long t, double power) {
        return new TelemetrySample("dev-1", T0 + t, 220d, 10d, power, t);
    }

    @Test
    void energyIsTrapezoidalAndOrderIndependent() {
        WindowAccumulator ordered = new WindowAccumulator(T0, W);
        ordered.accept(s(0, 100));
        ordered.accept(s(1_000, 200));
        ordered.accept(s(3_000, 400));

        WindowAccumulator shuffled = new WindowAccumulator(T0, W);
        shuffled.accept(s(3_000, 400));
        shuffled.accept(s(0, 100));
        shuffled.accept(s(1_000, 200));

        WindowStats a = ordered.toStats();
        WindowStats b = shuffled.toStats();
        // 能量 = (100+200)/2*1000ms + (200+400)/2*2000ms = 150000+600000 = 750000 W·ms
        double expectedEnergyWh = 750_000d / 3_600_000d;
        double expectedAvg = 750_000d / 3000d; // 750000 W·ms / 3000ms
        assertEquals(3, a.sampleCount());
        assertEquals(400d, a.peakPower(), 1e-9);
        assertEquals(expectedEnergyWh, a.energyWh(), 1e-12);
        assertEquals(expectedAvg, a.avgPower(), 1e-9);
        assertEquals(a.avgPower(), b.avgPower(), 1e-12, "乱序插入平均功率必须一致");
        assertEquals(a.energyWh(), b.energyWh(), 1e-15, "乱序插入能耗必须一致");
        assertEquals(a.peakPower(), b.peakPower(), 1e-15);
        log.info("窗口[{}..{}] 采样数={} 平均功率={}W 峰值={}W 能耗={}Wh（乱序插入结论一致）",
                T0, T0 + W, a.sampleCount(), a.avgPower(), a.peakPower(), a.energyWh());
    }

    @Test
    void singlePointDegradesToItsPower() {
        WindowAccumulator acc = new WindowAccumulator(T0, W);
        acc.accept(s(0, 333d));
        WindowStats stats = acc.toStats();
        assertEquals(1, stats.sampleCount());
        assertEquals(333d, stats.avgPower(), 1e-9);
        assertEquals(333d, stats.peakPower(), 1e-9);
        assertEquals(0d, stats.energyWh(), 1e-12);
        log.info("单点窗口 平均功率={}W 能耗={}Wh（无相邻点时按该点功率计，能耗为0）",
                stats.avgPower(), stats.energyWh());
    }

    @Test
    void closedWindowRejectsNoMorePoints() {
        WindowAccumulator acc = new WindowAccumulator(T0, W);
        assertFalse(acc.isClosed());
        acc.setClosed(true);
        assertTrue(acc.isClosed());
        assertTrue(acc.accept(s(5_000, 1d))); // accumulator 本身不拦截，封存判定在 WindowManager
        WindowStats stats = acc.toStats();
        assertTrue(stats.closed());
    }
}
