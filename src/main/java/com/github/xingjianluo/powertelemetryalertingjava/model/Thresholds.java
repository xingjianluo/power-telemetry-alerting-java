package com.github.xingjianluo.powertelemetryalertingjava.model;

/**
 * 单台设备的阈值配置。null 表示该检测项不启用。
 *
 * @param overVoltage   过压阈值（V），电压大于该值视为违规
 * @param underVoltage  欠压阈值（V），电压小于该值视为违规
 * @param overCurrent   过流阈值（A）
 * @param overPower     过功率阈值（W）
 * @param maxSampleGap  允许的最大采样间隔（毫秒），相邻采样或距当前时间超过该值判定采样中断
 */
public record Thresholds(Double overVoltage,
                         Double underVoltage,
                         Double overCurrent,
                         Double overPower,
                         Long maxSampleGap) {

    /** 使用默认阈值与中断间隔。 */
    public static Thresholds defaults() {
        return new Thresholds(null, null, null, null, null);
    }
}
