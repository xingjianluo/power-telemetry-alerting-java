package com.github.xingjianluo.powertelemetryalertingjava.store;

import com.github.xingjianluo.powertelemetryalertingjava.engine.WindowAccumulator;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertEvent;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertStatus;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.TelemetrySample;

import java.util.EnumMap;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单台设备的全部内存状态：配置、采样、窗口、告警状态机、事件历史。
 * 所有读写必须持有 {@link #lock}。
 */
public final class DeviceState {

    private final ReentrantLock lock = new ReentrantLock(true);
    private DeviceConfig config;
    private String tokenHash;
    private long registeredAt;
    private final TreeMap<Long, TelemetrySample> samples = new TreeMap<>();
    private long watermark = Long.MIN_VALUE;
    private final TreeMap<Long, WindowAccumulator> windows = new TreeMap<>();
    private final EnumMap<AlertType, AlertStatus> alertStatuses = new EnumMap<>(AlertType.class);
    private List<AlertEvent> alertEvents = new java.util.ArrayList<>();
    private long alertEventSeq;

    public DeviceState() {
    }

    public DeviceState(DeviceConfig config, String tokenHash, long registeredAt) {
        this.config = config;
        this.tokenHash = tokenHash;
        this.registeredAt = registeredAt;
    }

    @JsonIgnore
    public ReentrantLock getLock() { return lock; }
    public DeviceConfig getConfig() { return config; }
    public void setConfig(DeviceConfig config) { this.config = config; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public long getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(long registeredAt) { this.registeredAt = registeredAt; }
    public TreeMap<Long, TelemetrySample> getSamples() { return samples; }
    public long getWatermark() { return watermark; }
    public void setWatermark(long watermark) { this.watermark = watermark; }
    public TreeMap<Long, WindowAccumulator> getWindows() { return windows; }
    public EnumMap<AlertType, AlertStatus> getAlertStatuses() { return alertStatuses; }
    public List<AlertEvent> getAlertEvents() { return alertEvents; }
    public void setAlertEvents(List<AlertEvent> alertEvents) { this.alertEvents = alertEvents; }
    public long getAlertEventSeq() { return alertEventSeq; }
    public void setAlertEventSeq(long alertEventSeq) { this.alertEventSeq = alertEventSeq; }

    /** 追加一条告警事件，返回事件编号。 */
    public long appendAlertEvent(AlertEvent event) {
        alertEvents.add(event);
        return event.id();
    }
}
