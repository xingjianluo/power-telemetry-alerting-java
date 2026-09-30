package com.github.xingjianluo.powertelemetryalertingjava.tests.support;

import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceState;
import com.github.xingjianluo.powertelemetryalertingjava.storage.DeviceStateRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内存储：用于重启恢复测试（同一存储上重建 registry 即等价重启）。 */
public class InMemoryStateRepository implements DeviceStateRepository {

    private final Map<String, DeviceState> store = new ConcurrentHashMap<>();

    @Override
    public Optional<DeviceState> load(String deviceId) {
        return Optional.ofNullable(store.get(deviceId));
    }

    @Override
    public void save(DeviceState state) {
        store.put(state.getDeviceId(), state);
    }
}
