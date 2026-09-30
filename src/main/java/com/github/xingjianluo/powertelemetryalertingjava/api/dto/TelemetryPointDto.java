package com.github.xingjianluo.powertelemetryalertingjava.api.dto;

/**
 * 一条上报点。时间戳为 epoch 毫秒；字段缺失由服务端逐条拒绝。
 */
public record TelemetryPointDto(Long timestamp,
                                Double voltage,
                                Double current,
                                Double power) {
}
