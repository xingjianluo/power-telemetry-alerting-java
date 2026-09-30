package com.github.xingjianluo.powertelemetryalertingjava.model;

/**
 * 被拒绝的记录及其原因，便于整批返回时逐条区分。
 *
 * @param index       该记录在上报批次中的下标（从 0 开始，缺失设备号时仍按批次下标）
 * @param deviceId    设备编号（可为空，缺失时为 null）
 * @param sampleTime  采样时间（缺失时为 -1）
 * @param code        拒绝原因
 * @param reason      人类可读说明
 */
public record RejectedRecord(int index,
                             String deviceId,
                             long sampleTime,
                             RejectCode code,
                             String reason) {
}
