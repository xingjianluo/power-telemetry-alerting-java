package com.github.xingjianluo.powertelemetryalertingjava.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.xingjianluo.powertelemetryalertingjava.model.AlertEvent;
import com.github.xingjianluo.powertelemetryalertingjava.model.TelemetrySample;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地 JSON 快照：原子写入临时文件后 move，服务启动时恢复全部设备状态。
 * 不依赖数据库 / 消息中间件 / 云端。
 *
 * <p>保存时逐设备加锁并复制为纯数据结构（不带锁），
 * 加载时重建 {@link DeviceState}，窗口与告警状态机随之恢复。</p>
 */
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    private final Path file;
    private final ObjectMapper mapper;

    public SnapshotStore(String file) {
        this.file = Path.of(file);
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public Path getFile() {
        return file;
    }

    /** 原子落盘全部设备状态。 */
    public synchronized void save(DeviceRegistry registry) {
        try {
            Map<String, DeviceState> snapshot = new LinkedHashMap<>();
            for (DeviceState state : registry.all()) {
                state.getLock().lock();
                try {
                    snapshot.put(state.getConfig().deviceId(), state);
                } finally {
                    state.getLock().unlock();
                }
            }
            byte[] bytes = mapper.writeValueAsBytes(snapshot);
            atomicWrite(bytes);
        } catch (Exception e) {
            // 快照失败不影响内存中的处理结论，仅记录；下次批次会重试
            log.error("快照写入失败 file={}", file, e);
        }
    }

    /** 启动加载快照到注册表；文件不存在时静默返回。 */
    public synchronized void loadInto(DeviceRegistry registry) {
        if (!Files.exists(file)) {
            log.info("无历史快照 file={}，以空状态启动", file);
            return;
        }
        try {
            JavaRestored restored = readWith(registry);
            log.info("快照加载完成 file={} devices={}", file, restored.devices);
        } catch (Exception e) {
            log.error("快照加载失败，以空状态启动 file={}", file, e);
        }
    }

    private JavaRestored readWith(DeviceRegistry registry) throws IOException {
        var type = mapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, DeviceState.class);
        Map<String, DeviceState> data = mapper.readValue(Files.readAllBytes(file), type);
        Map<String, DeviceState> restored = new LinkedHashMap<>();
        for (Map.Entry<String, DeviceState> e : data.entrySet()) {
            DeviceState s = e.getValue();
            if (s.getConfig() == null) {
                log.warn("快照中设备 {} 缺少配置，跳过", e.getKey());
                continue;
            }
            restored.put(e.getKey(), s);
        }
        registry.putAll(restored);
        return new JavaRestored(restored.size());
    }

    protected void atomicWrite(byte[] bytes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record JavaRestored(int devices) {
    }
}
