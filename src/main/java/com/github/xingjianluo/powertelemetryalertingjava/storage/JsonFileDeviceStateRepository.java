package com.github.xingjianluo.powertelemetryalertingjava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * JSON 文件存储：data/&lt;deviceId&gt;.json。
 * 先写临时文件再原子 move，进程在任意时刻崩溃都不会留下半个状态文件；
 * 读取到损坏文件时隔离为 .corrupt 并从空状态启动，不让坏文件拖垮服务。
 */
@Repository
public class JsonFileDeviceStateRepository implements DeviceStateRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonFileDeviceStateRepository.class);

    private final ObjectMapper mapper;
    private final ConfigProperties props;

    public JsonFileDeviceStateRepository(ObjectMapper mapper, ConfigProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    private Path fileOf(String deviceId) {
        return Path.of(props.getDataDir(), sanitize(deviceId) + ".json");
    }

    /** 设备 ID 已在接入层校验，这里再做一次路径安全防护。 */
    static String sanitize(String deviceId) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid deviceId: " + deviceId);
        }
        return deviceId;
    }

    @Override
    public Optional<DeviceState> load(String deviceId) {
        Path file = fileOf(deviceId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            DeviceState state = mapper.readValue(file.toFile(), DeviceState.class);
            log.info("state loaded from disk: device={} samples={} windows={} events={}",
                    deviceId, state.getSamples().size(), state.getWindows().size(),
                    state.getEvents().size());
            return Optional.of(state);
        } catch (IOException e) {
            Path quarantined = Path.of(file + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(file, quarantined, StandardCopyOption.REPLACE_EXISTING);
                log.error("corrupt state file quarantined: {} -> {} ({})", file, quarantined, e.getMessage());
            } catch (IOException moveError) {
                log.error("corrupt state file could not be quarantined: {} ({})", file, moveError.getMessage());
            }
            return Optional.empty();
        }
    }

    @Override
    public synchronized void save(DeviceState state) {
        Path file = fileOf(state.getDeviceId());
        Path tmp = Path.of(file + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("state persisted: device={} revision={}", state.getDeviceId(), state.getRevision());
        } catch (IOException e) {
            log.error("state persist failed: device={} ({})", state.getDeviceId(), e.getMessage());
            throw new IllegalStateException("failed to persist state for device " + state.getDeviceId(), e);
        }
    }
}
