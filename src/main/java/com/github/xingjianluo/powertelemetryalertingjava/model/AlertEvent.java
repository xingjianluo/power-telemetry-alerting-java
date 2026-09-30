package com.github.xingjianluo.powertelemetryalertingjava.model;

/**
 * 一条可追溯的告警生命周期事件（触发或恢复）。
 *
 * @param id          事件编号（设备内递增）
 * @param deviceId    设备编号
 * @param type        告警类型
 * @param state       ACTIVE / RECOVERED
 * @param sampleTime  触发/恢复所依据的采样时间；SAMPLE_INTERRUPT 恢复为恢复采样时间
 * @param eventTime   事件判定时间（服务时钟）
 * @param detail      判定依据（阈值、连续次数、实际值等）
 */
public record AlertEvent(long id,
                         String deviceId,
                         AlertType type,
                         AlertState state,
                         long sampleTime,
                         long eventTime,
                         String detail) {
}
