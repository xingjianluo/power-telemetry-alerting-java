package com.github.xingjianluo.powertelemetryalertingjava.model;

/** 批次内单条记录的处理结论（保留原始下标，方便定位）。 */
public record RecordResult(int index, Long timestampMs, RecordStatus status,
                           RejectReason reason, String message) {

    public static RecordResult accepted(int index, long timestampMs) {
        return new RecordResult(index, timestampMs, RecordStatus.ACCEPTED, null, "accepted");
    }

    public static RecordResult rejected(int index, Long timestampMs, RejectReason reason, String message) {
        return new RecordResult(index, timestampMs, RecordStatus.REJECTED, reason, message);
    }

    public static RecordResult duplicate(int index, long timestampMs) {
        return new RecordResult(index, timestampMs, RecordStatus.DUPLICATE, RejectReason.DUPLICATE,
                "identical sample already stored for this device and timestamp");
    }
}
