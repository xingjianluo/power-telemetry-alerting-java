package com.github.xingjianluo.powertelemetryalertingjava.storage;

import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceState;

import java.util.Optional;

/** 设备状态持久化（本地 JSON 文件，单设备一个文件、原子替换）。 */
public interface DeviceStateRepository {

    Optional<DeviceState> load(String deviceId);

    void save(DeviceState state);
}
