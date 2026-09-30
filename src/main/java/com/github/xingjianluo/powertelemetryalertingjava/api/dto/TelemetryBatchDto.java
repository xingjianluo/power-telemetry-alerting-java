package com.github.xingjianluo.powertelemetryalertingjava.api.dto;

import java.util.List;

/**
 * 一批上报点（同属 URL 中的设备）。
 */
public record TelemetryBatchDto(List<TelemetryPointDto> points) {
}
