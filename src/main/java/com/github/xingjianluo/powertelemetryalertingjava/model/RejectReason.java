package com.github.xingjianluo.powertelemetryalertingjava.model;

/** 单条记录被拒绝/特殊处理的可区分原因。 */
public enum RejectReason {
    MISSING_TIMESTAMP,
    MISSING_VOLTAGE,
    MISSING_CURRENT,
    MISSING_POWER,
    INVALID_VALUE,
    FUTURE_TIMESTAMP,
    TOO_OLD,
    TIME_ROLLBACK,
    RETENTION_EXCEEDED,
    DUPLICATE,
    CONFLICT
}
