package com.github.xingjianluo.powertelemetryalertingjava.engine;

import com.github.xingjianluo.powertelemetryalertingjava.config.ServiceProperties;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.Thresholds;
import com.github.xingjianluo.powertelemetryalertingjava.persistence.SnapshotStore;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.store.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 设备注册与配置服务。
 *
 * <p>所有配置变更只锁目标设备：给一台设备调阈值/启停告警不影响其他设备接入；
 * 非法配置在修改前抛 IllegalArgumentException，已有状态保持不变并照常落盘。</p>
 */
@Service
public class DeviceConfigService {

    private static final Logger log = LoggerFactory.getLogger(DeviceConfigService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRegistry registry;
    private final SnapshotStore snapshotStore;
    private final ServiceProperties properties;
    private final LongSupplier clock;

    public DeviceConfigService(DeviceRegistry registry, SnapshotStore snapshotStore,
                               ServiceProperties properties, LongSupplier clock) {
        this.registry = registry;
        this.snapshotStore = snapshotStore;
        this.properties = properties;
        this.clock = clock;
    }

    /** 注册设备并返回一次性明文令牌；重复注册被拒绝。 */
    public String register(String deviceId, String name) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("设备编号不能为空");
        }
        String displayName = (name == null || name.isBlank()) ? deviceId : name;
        String raw = generateToken();
        DeviceConfig config = new DeviceConfig(deviceId, displayName, Thresholds.defaults(), true);
        registry.register(config, hashToken(raw), clock.getAsLong());
        snapshotStore.save(registry);
        log.info("注册设备 device={} name={}", deviceId, displayName);
        return raw;
    }

    public DeviceConfig get(String deviceId) {
        DeviceState state = requireState(deviceId);
        state.getLock().lock();
        try {
            return state.getConfig();
        } finally {
            state.getLock().unlock();
        }
    }

    public List<DeviceConfig> list() {
        return registry.all().stream().map(s -> {
            s.getLock().lock();
            try {
                return s.getConfig();
            } finally {
                s.getLock().unlock();
            }
        }).sorted(java.util.Comparator.comparing(DeviceConfig::deviceId)).toList();
    }

    /** 更新阈值：先完整校验再落锁生效，非法值不会影响现有状态。 */
    public DeviceConfig updateThresholds(String deviceId, Thresholds requested) {
        if (requested == null) {
            throw new IllegalArgumentException("阈值请求体不能为空");
        }
        validateThresholds(requested);
        DeviceState state = requireState(deviceId);
        state.getLock().lock();
        try {
            DeviceConfig updated = state.getConfig().withThresholds(requested);
            state.setConfig(updated);
            snapshotStore.save(registry);
            log.info("更新阈值 device={} thresholds={}", deviceId, requested);
            return updated;
        } finally {
            state.getLock().unlock();
        }
    }

    /** 启停某设备告警；停用时不产生新告警，已有历史事件保留。 */
    public DeviceConfig setAlertsEnabled(String deviceId, boolean enabled) {
        DeviceState state = requireState(deviceId);
        state.getLock().lock();
        try {
            DeviceConfig updated = state.getConfig().withAlertsEnabled(enabled);
            state.setConfig(updated);
            snapshotStore.save(registry);
            log.info("告警启停 device={} enabled={}", deviceId, enabled);
            return updated;
        } finally {
            state.getLock().unlock();
        }
    }

    /** 校验设备令牌（明文比对哈希）。 */
    public boolean checkToken(String deviceId, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        DeviceState state = registry.get(deviceId);
        if (state == null) {
            return false;
        }
        state.getLock().lock();
        try {
            String hash = state.getTokenHash();
            return hash != null && MessageDigest.isEqual(
                    hash.getBytes(StandardCharsets.UTF_8),
                    hashToken(rawToken).getBytes(StandardCharsets.UTF_8));
        } finally {
            state.getLock().unlock();
        }
    }

    private DeviceState requireState(String deviceId) {
        DeviceState state = registry.get(deviceId);
        if (state == null) {
            throw new IllegalStateException("设备不存在: " + deviceId);
        }
        return state;
    }

    /**
     * 阈值合法性：启用项必须有限且为正；欠压必须小于过压；间隔为正数。
     * null 表示该检测项回落全局默认或关闭。
     */
    private void validateThresholds(Thresholds t) {
        requirePositive(t.overVoltage(), "overVoltage");
        requirePositive(t.underVoltage(), "underVoltage");
        requirePositive(t.overCurrent(), "overCurrent");
        requirePositive(t.overPower(), "overPower");
        if (t.maxSampleGap() != null && t.maxSampleGap() <= 0) {
            throw new IllegalArgumentException("maxSampleGap 必须为正数（毫秒），实际: " + t.maxSampleGap());
        }
        if (t.overVoltage() != null && t.underVoltage() != null
                && t.underVoltage() >= t.overVoltage()) {
            throw new IllegalArgumentException(
                    "欠压阈值必须小于过压阈值: under=" + t.underVoltage() + " over=" + t.overVoltage());
        }
    }

    private static void requirePositive(Double v, String field) {
        if (v != null && (!Double.isFinite(v) || v <= 0d)) {
            throw new IllegalArgumentException(field + " 必须为有限正数，实际: " + v);
        }
    }

    public static String hashToken(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String generateToken() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
