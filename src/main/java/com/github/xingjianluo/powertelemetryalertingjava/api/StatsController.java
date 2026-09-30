package com.github.xingjianluo.powertelemetryalertingjava.api;

import com.github.xingjianluo.powertelemetryalertingjava.engine.StatsQueryService;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertEvent;
import com.github.xingjianluo.powertelemetryalertingjava.model.WindowStats;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 统计与告警查询接口（只读）。
 */
@RestController
@RequestMapping("/api/devices/{deviceId}")
public class StatsController {

    private final StatsQueryService statsService;
    private final DeviceRegistry registry;

    public StatsController(StatsQueryService statsService, DeviceRegistry registry) {
        this.statsService = statsService;
        this.registry = registry;
    }

    @GetMapping("/windows")
    public List<WindowStats> windows(@PathVariable String deviceId,
                                     @RequestParam(defaultValue = "20") int limit) {
        return statsService.windows(deviceId, limit);
    }

    @GetMapping("/alerts")
    public List<AlertEvent> alerts(@PathVariable String deviceId,
                                   @RequestParam(required = false) String state) {
        DeviceState s = registry.get(deviceId);
        if (s == null) {
            return List.of();
        }
        s.getLock().lock();
        try {
            List<AlertEvent> events = new ArrayList<>(s.getAlertEvents());
            if (state != null) {
                events.removeIf(e -> !e.state().name().equalsIgnoreCase(state));
            }
            events.sort(Comparator.comparingLong(AlertEvent::id));
            return events;
        } finally {
            s.getLock().unlock();
        }
    }
}
