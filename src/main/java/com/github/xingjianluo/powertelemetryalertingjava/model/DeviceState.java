package com.github.xingjianluo.powertelemetryalertingjava.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 单台设备的完整持久化状态：
 * 已接受采样点（按采样时间排序，去重锚点）、窗口聚合、告警运行时、告警事件与水位线。
 */
public class DeviceState {
    private String deviceId;
    private DeviceConfig configOverride;
    private TreeMap<Long, Sample> samples = new TreeMap<>();
    private TreeMap<Long, WindowData> windows = new TreeMap<>();
    private Map<AlertType, AlertRuntime> alertRuntimes = new EnumMap<>(AlertType.class);
    private List<AlertEvent> events = new ArrayList<>();
    private long alertWatermarkMs = Long.MIN_VALUE;
    private long lastIngestAtMs;
    private long lastSampleMs;
    private long revision;

    public DeviceState() {}

    public DeviceState(String deviceId) {
        this.deviceId = deviceId;
        for (AlertType t : AlertType.values()) {
            alertRuntimes.put(t, new AlertRuntime(t));
        }
    }

    public AlertRuntime runtime(AlertType type) {
        return alertRuntimes.computeIfAbsent(type, AlertRuntime::new);
    }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public DeviceConfig getConfigOverride() { return configOverride; }
    public void setConfigOverride(DeviceConfig configOverride) { this.configOverride = configOverride; }
    public TreeMap<Long, Sample> getSamples() { return samples; }
    public void setSamples(TreeMap<Long, Sample> samples) { this.samples = samples; }
    public TreeMap<Long, WindowData> getWindows() { return windows; }
    public void setWindows(TreeMap<Long, WindowData> windows) { this.windows = windows; }
    public Map<AlertType, AlertRuntime> getAlertRuntimes() { return alertRuntimes; }
    public void setAlertRuntimes(Map<AlertType, AlertRuntime> alertRuntimes) { this.alertRuntimes = alertRuntimes; }
    public List<AlertEvent> getEvents() { return events; }
    public void setEvents(List<AlertEvent> events) { this.events = events; }
    public long getAlertWatermarkMs() { return alertWatermarkMs; }
    public void setAlertWatermarkMs(long alertWatermarkMs) { this.alertWatermarkMs = alertWatermarkMs; }
    public long getLastIngestAtMs() { return lastIngestAtMs; }
    public void setLastIngestAtMs(long lastIngestAtMs) { this.lastIngestAtMs = lastIngestAtMs; }
    public long getLastSampleMs() { return lastSampleMs; }
    public void setLastSampleMs(long lastSampleMs) { this.lastSampleMs = lastSampleMs; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
}
