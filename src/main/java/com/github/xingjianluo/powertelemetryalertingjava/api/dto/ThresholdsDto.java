package com.github.xingjianluo.powertelemetryalertingjava.api.dto;

/**
 * 阈值更新请求体；null 字段表示不启用对应检测项（maxSampleGap 用 null 关闭中断检测）。
 */
public record ThresholdsDto(Double overVoltage,
                            Double underVoltage,
                            Double overCurrent,
                            Double overPower,
                            Long maxSampleGap) {
}
