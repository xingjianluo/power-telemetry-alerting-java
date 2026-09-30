package com.github.xingjianluo.powertelemetryalertingjava.model;

/**
 * 一个固定时间窗口内的统计结果。
 *
 * @param windowStart 窗口起始时间（epoch 毫秒，左闭右开 [start, start+length)）
 * @param windowLength 窗口长度（毫秒）
 * @param sampleCount 窗口内采样数（去重后）
 * @param avgPower    平均功率（W），按采样间隔做梯形积分 / 时间长度
 * @param peakPower   峰值功率（W）
 * @param energyWh    能耗（Wh）
 * @param closed      窗口是否已封存（迟到窗口关闭后不再变更）
 */
public record WindowStats(long windowStart,
                          long windowLength,
                          int sampleCount,
                          double avgPower,
                          double peakPower,
                          double energyWh,
                          boolean closed) {
}
