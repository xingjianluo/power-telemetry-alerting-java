package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.model.TelemetrySample;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import org.springframework.stereotype.Component;

import java.util.TreeMap;

/**
 * 窗口生命周期：窗口定位、点落入、按水位线封存旧窗口、按保留数量裁剪。
 * 窗口为固定长度的滚动窗口，左闭右开 [n*L, (n+1)*L)。
 */
@Component
public class WindowManager {

    /**
     * 把一个采样放入对应窗口；窗口已封存则返回 false。
     */
    public boolean accept(DeviceState state, TelemetrySample sample, long windowMs) {
        long start = floorStart(sample.sampleTime(), windowMs);
        TreeMap<Long, WindowAccumulator> windows = state.getWindows();
        WindowAccumulator acc = windows.get(start);
        if (acc == null) {
            acc = new WindowAccumulator(start, windowMs);
            windows.put(start, acc);
        }
        if (acc.isClosed()) {
            return false;
        }
        return acc.accept(sample);
    }

    /** 水位线推进后封存不再可能接收迟到点的窗口，并裁剪过多的历史窗口。 */
    public void closeAndEvict(DeviceState state, long watermark, long windowMs,
                              long allowedLatenessMs, int retainedWindows) {
        TreeMap<Long, WindowAccumulator> windows = state.getWindows();
        long closeFloorStart = floorStart(watermark - allowedLatenessMs, windowMs);
        for (WindowAccumulator acc : windows.values()) {
            if (!acc.isClosed() && acc.getWindowStart() < closeFloorStart) {
                acc.setClosed(true);
            }
        }
        while (windows.size() > retainedWindows) {
            Long oldest = windows.firstKey();
            windows.remove(oldest);
        }
    }

    public static long floorStart(long time, long windowMs) {
        return Math.floorDiv(time, windowMs) * windowMs;
    }
}
