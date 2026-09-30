package com.github.xingjianluo.powertelemetryalertingjava.api;

import com.github.xingjianluo.powertelemetryalertingjava.api.dto.AlertsEnabledDto;
import com.github.xingjianluo.powertelemetryalertingjava.api.dto.RegisterDeviceDto;
import com.github.xingjianluo.powertelemetryalertingjava.api.dto.RegisterResultDto;
import com.github.xingjianluo.powertelemetryalertingjava.api.dto.ThresholdsDto;
import com.github.xingjianluo.powertelemetryalertingjava.engine.DeviceConfigService;
import com.github.xingjianluo.powertelemetryalertingjava.model.DeviceConfig;
import com.github.xingjianluo.powertelemetryalertingjava.model.Thresholds;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备注册、阈值配置、告警启停。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceConfigService configService;

    public DeviceController(DeviceConfigService configService) {
        this.configService = configService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResultDto> register(@RequestBody RegisterDeviceDto request) {
        return ResponseEntity.ok(new RegisterResultDto(request.deviceId(), configService.register(request.deviceId(), request.name())));
    }

    @GetMapping
    public List<DeviceConfig> list() {
        return configService.list();
    }

    @GetMapping("/{deviceId}/config")
    public DeviceConfig get(@PathVariable String deviceId) {
        return configService.get(deviceId);
    }

    @PostMapping("/{deviceId}/thresholds")
    public DeviceConfig updateThresholds(@PathVariable String deviceId,
                                         @RequestBody ThresholdsDto request) {
        return configService.updateThresholds(deviceId,
                new Thresholds(request.overVoltage(), request.underVoltage(),
                        request.overCurrent(), request.overPower(), request.maxSampleGap()));
    }

    @PostMapping("/{deviceId}/alerts")
    public DeviceConfig setAlertsEnabled(@PathVariable String deviceId,
                                         @RequestBody AlertsEnabledDto request) {
        return configService.setAlertsEnabled(deviceId, request.enabled());
    }
}
