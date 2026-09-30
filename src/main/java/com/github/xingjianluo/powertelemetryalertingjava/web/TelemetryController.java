package com.github.xingjianluo.powertelemetryalertingjava.web;

import com.github.xingjianluo.powertelemetryalertingjava.config.ConfigProperties;
import com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceRegistry;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceState;
import com.github.xingjianluo.powertelemetryalertingjava.model.EffectiveConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.IngestBatchResult;
import com.github.xingjianluo.powertelemetryalertingjava.model.RawSample;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 设备接入、状态查询与设备级配置管理接口。 */
@RestController
@RequestMapping("/api")
public class TelemetryController {

    private final DeviceRegistry registry;
    private final ConfigProperties props;

    public TelemetryController(DeviceRegistry registry, ConfigProperties props) {
        this.registry = registry;
        this.props = props;
    }

    /** 批量上报：设备 ID 放在路径中。 */
    @PostMapping("/telemetry/{deviceId}")
    public IngestBatchResult ingestByPath(@PathVariable String deviceId,
                                          @RequestBody(required = false) IngestRequest body) {
        String id = DeviceRegistry.validateDeviceId(deviceId);
        List<RawSample> samples = body != null && body.samples() != null ? body.samples() : List.of();
        if (samples.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "samples must not be empty");
        }
        return registry.ingest(id, samples);
    }

    /** 批量上报：设备 ID 放在请求体中。 */
    @PostMapping("/telemetry")
    public IngestBatchResult ingestByBody(@RequestBody(required = false) IngestRequest body) {
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        String id = DeviceRegistry.validateDeviceId(body.deviceId());
        List<RawSample> samples = body.samples() != null ? body.samples() : List.of();
        if (samples.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "samples must not be empty");
        }
        return registry.ingest(id, samples);
    }

    /** 查询设备当前状态（窗口、告警状态、最近事件）。 */
    @GetMapping("/devices/{deviceId}")
    public DeviceState deviceState(@PathVariable String deviceId) {
        return registry.state(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "device not found: " + deviceId));
    }

    @GetMapping("/devices")
    public Map<String, Object> devices() {
        return Map.of("deviceIds", registry.deviceIds().stream().sorted().toList());
    }

    /** 查询设备生效配置（默认值 + 覆盖合并结果）。 */
    @GetMapping("/devices/{deviceId}/config")
    public Map<String, Object> getConfig(@PathVariable String deviceId) {
        String id = DeviceRegistry.validateDeviceId(deviceId);
        DeviceConfig override = registry.getConfigOverride(id);
        EffectiveConfig effective = EffectiveConfig.merge(props, override);
        return Map.of(
                "deviceId", id,
                "override", override == null ? Map.of() : override,
                "effective", Map.of(
                        "overVoltageV", effective.overVoltageV(),
                        "underVoltageV", effective.underVoltageV(),
                        "overCurrentA", effective.overCurrentA(),
                        "overPowerW", effective.overPowerW(),
                        "interruptionTimeoutMs", effective.interruptionTimeoutMs(),
                        "breachTimes", effective.breachTimes(),
                        "recoverTimes", effective.recoverTimes(),
                        "alertsEnabled", effective.alertsEnabled()));
    }

    /** 部分更新设备配置（只覆盖提供的字段）。 */
    @PatchMapping("/devices/{deviceId}/config")
    public Map<String, Object> patchConfig(@PathVariable String deviceId,
                                           @RequestHeader(value = "X-Admin-Token", required = false) String token,
                                           @RequestBody(required = false) DeviceConfig patch) {
        requireAdmin(token);
        String id = DeviceRegistry.validateDeviceId(deviceId);
        if (patch == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        registry.updateConfig(id, patch, false);
        return getConfig(id);
    }

    /** 全量替换设备配置。 */
    @PutMapping("/devices/{deviceId}/config")
    public Map<String, Object> putConfig(@PathVariable String deviceId,
                                         @RequestHeader(value = "X-Admin-Token", required = false) String token,
                                         @RequestBody(required = false) DeviceConfig config) {
        requireAdmin(token);
        String id = DeviceRegistry.validateDeviceId(deviceId);
        if (config == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        registry.updateConfig(id, config, true);
        return getConfig(id);
    }

    /** 越权修改统一 401，不泄露任何设备状态。 */
    private void requireAdmin(String token) {
        if (props.getAdminToken() == null || props.getAdminToken().isBlank()
                || !props.getAdminToken().equals(token)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid or missing X-Admin-Token");
        }
    }
}
