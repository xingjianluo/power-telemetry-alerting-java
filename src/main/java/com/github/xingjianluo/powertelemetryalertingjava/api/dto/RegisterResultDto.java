package com.github.xingjianluo.powertelemetryalertingjava.api.dto;

/**
 * 注册结果；token 仅此一次明文返回。
 */
public record RegisterResultDto(String deviceId, String token) {
}
