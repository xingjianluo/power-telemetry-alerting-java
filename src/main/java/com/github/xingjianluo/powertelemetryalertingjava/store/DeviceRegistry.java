package com.github.xingjianluo.powertelemetryalertingjava.store;

import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.Thresholds;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备注册表：设备之间互相独立，单设备配置/启停不影响其他设备接入。
 */
@Component
public class DeviceRegistry {

    private final ConcurrentHashMap<String, DeviceState> devices = new ConcurrentHashMap<>();

    public DeviceState get(String deviceId) {
        return devices.get(deviceId);
    }

    /**
     * 遥测首次到达时自动建档（本地单机信任设备号；开启鉴权后无令牌设备无法通过拦截器）。
     */
    public DeviceState getOrCreate(String deviceId, long now) {
        return devices.computeIfAbsent(deviceId, id -> {
            DeviceConfig config = new DeviceConfig(id, id, Thresholds.defaults(), true);
            return new DeviceState(config, null, now);
        });
    }

    /**
     * 注册新设备；重复注册抛 IllegalStateException。
     */
    public DeviceState register(DeviceConfig config, String tokenHash, long now) {
        DeviceState state = new DeviceState(config, tokenHash, now);
        DeviceState existing = devices.putIfAbsent(config.deviceId(), state);
        if (existing != null) {
            throw new IllegalStateException("设备已存在: " + config.deviceId());
        }
        return state;
    }

    /** 快照恢复时直接放入（先清空）。 */
    public void putAll(java.util.Map<String, DeviceState> restored) {
        devices.putAll(restored);
    }

    public Collection<DeviceState> all() {
        return devices.values();
    }

    public int size() {
        return devices.size();
    }
}
