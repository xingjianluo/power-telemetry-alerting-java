package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.model.WindowStats;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 窗口统计只读查询：读取时持设备锁并即时重算，保证看到的统计与状态推进一致。
 */
@Service
public class StatsQueryService {

    private static final Logger log = LoggerFactory.getLogger(StatsQueryService.class);

    private final DeviceRegistry registry;

    public StatsQueryService(DeviceRegistry registry) {
        this.registry = registry;
    }

    /** 最近 limit 个窗口（按窗口起始时间倒序）。设备不存在返回空列表。 */
    public List<WindowStats> windows(String deviceId, int limit) {
        DeviceState state = registry.get(deviceId);
        if (state == null) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), 10_000);
        state.getLock().lock();
        try {
            List<WindowStats> all = new ArrayList<>(state.getWindows().size());
            for (WindowAccumulator acc : state.getWindows().values()) {
                all.add(acc.toStats());
            }
            all.sort(Comparator.comparingLong(WindowStats::windowStart).reversed());
            List<WindowStats> view = all.size() > capped ? all.subList(0, capped) : all;
            log.debug("查询窗口 device={} 返回 {}/{} 个窗口", deviceId, view.size(), all.size());
            return new ArrayList<>(view);
        } finally {
            state.getLock().unlock();
        }
    }

    /** 查询某采样时间所属窗口；不存在返回 null。 */
    public WindowStats windowAt(String deviceId, long sampleTime) {
        DeviceState state = registry.get(deviceId);
        if (state == null) {
            return null;
        }
        state.getLock().lock();
        try {
            // 窗口长度从任意已有窗口推断；无窗口则无法定位
            Long windowMs = state.getWindows().isEmpty() ? null
                    : state.getWindows().firstEntry().getValue().getWindowLength();
            if (windowMs == null) {
                return null;
            }
            long start = WindowManager.floorStart(sampleTime, windowMs);
            WindowAccumulator acc = state.getWindows().get(start);
            return acc == null ? null : acc.toStats();
        } finally {
            state.getLock().unlock();
        }
    }
}
