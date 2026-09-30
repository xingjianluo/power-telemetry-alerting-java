package com.github.xingjianluo.powertelemetryalertingjava.model;

/**
 * 设备配置：阈值 + 告警启停 + 注册名。
 * 设备级告警开关 enabled=false 时该设备不产生任何告警（但统计照常）。
 */
public record DeviceConfig(String deviceId,
                           String name,
                           Thresholds thresholds,
                           boolean alertsEnabled) {

    public DeviceConfig withThresholds(Thresholds t) {
        return new DeviceConfig(deviceId, name, t, alertsEnabled);
    }

    public DeviceConfig withAlertsEnabled(boolean enabled) {
        return new DeviceConfig(deviceId, name, thresholds, enabled);
    }
}
