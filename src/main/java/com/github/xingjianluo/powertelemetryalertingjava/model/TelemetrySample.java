package com.github.xingjianluo.powertelemetryalertingjava.model;

/**
 * 一条设备功率遥测采样。
 *
 * @param deviceId    设备编号
 * @param sampleTime  采样时间（epoch 毫秒）
 * @param voltage     电压（V）
 * @param current     电流（A）
 * @param power       功率（W）
 * @param receivedAt  服务接收时间（epoch 毫秒，用于审计）
 */
public record TelemetrySample(String deviceId,
                              long sampleTime,
                              double voltage,
                              double current,
                              double power,
                              long receivedAt) implements Comparable<TelemetrySample> {

    @Override
    public int compareTo(TelemetrySample o) {
        int byTime = Long.compare(this.sampleTime, o.sampleTime);
        if (byTime != 0) {
            return byTime;
        }
        return this.deviceId.compareTo(o.deviceId);
    }
}
