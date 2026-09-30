package com.github.xingjianluo.powertelemetryalertingjava.model;

import java.util.List;

/**
 * 一批上报的处理结果：接受与拒绝互不影响，坏点不会拖垮整批。
 *
 * @param deviceId     批次对应的设备
 * @param accepted     本次真正新接受（非重复）的采样数
 * @param duplicate    命中重复、未重复累计的采样数
 * @param rejected     被拒绝的记录及原因
 * @param alertsRaised 本次推进新触发的告警
 * @param alertsCleared 本次推进新恢复的告警
 */
public record IngestResult(String deviceId,
                           int accepted,
                           int duplicate,
                           List<RejectedRecord> rejected,
                           List<AlertEvent> alertsRaised,
                           List<AlertEvent> alertsCleared) {
}
