package com.github.xingjianluo.powertelemetryalertingjava.model;

import java.util.List;

/** 批量上报的整体结论：坏点不拖垮整批，逐条返回。 */
public record IngestBatchResult(String deviceId, int total, int accepted, int duplicate, int rejected,
                                List<RecordResult> records, List<AlertEvent> alerts) {
}
