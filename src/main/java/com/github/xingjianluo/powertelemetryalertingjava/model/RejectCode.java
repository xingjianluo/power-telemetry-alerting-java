package com.github.xingjianluo.powertelemetryalertingjava.model;

/**
 * 单条遥测记录被拒绝的可区分原因。
 */
public enum RejectCode {
    /** 关键字段缺失（设备号、采样时间、电压、电流、功率）。 */
    MISSING_FIELD,
    /** 采样时间晚于服务当前时间 + 允许未来偏差。 */
    FUTURE_TIMESTAMP,
    /** 采样时间早于允许的最大历史范围。 */
    OUT_OF_RANGE,
    /** 相对该设备已知水位线发生明显回退（超出允许乱序迟到窗口）。 */
    TIME_ROLLBACK,
    /** 同一设备、同一采样时间的重复上报。 */
    DUPLICATE,
    /** 数值非法（NaN/无穷/负数等）。 */
    INVALID_VALUE
}
