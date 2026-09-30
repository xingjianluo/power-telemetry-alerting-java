package com.github.xingjianluo.powertelemetryalertingjava.web;

import com.github.xingjianluo.powertelemetryalertingjava.model.RawSample;

import java.util.List;

/** 设备批量上报请求体。 */
public record IngestRequest(String deviceId, List<RawSample> samples) {
}
