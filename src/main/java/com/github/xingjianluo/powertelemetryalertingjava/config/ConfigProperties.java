package com.github.xingjianluo.powertelemetryalertingjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 全局配置（application.properties 中 power.telemetry.* 覆盖）。 */
@ConfigurationProperties(prefix = "power.telemetry")
public class ConfigProperties {

    /** 窗口长度，毫秒，默认 60_000。 */
    private long windowMs = 60_000L;
    /** 为乱序到达保留多少个窗口。 */
    private int retainWindows = 15;
    /** 允许的轻微未来时间偏差。 */
    private long futureSkewMs = 60_000L;
    /** 早于 (now - maxAgeMs) 的数据拒绝。 */
    private long maxAgeMs = 86_400_000L;
    /** 允许乱序回退的范围，超过视为明显时间回退。 */
    private long reorderLagMs = 600_000L;
    /** 本地状态文件目录。 */
    private String dataDir = "data";
    /** 写接口管理令牌。 */
    private String adminToken = "local-admin-token";
    /** 后台中断扫描间隔。 */
    private long tickIntervalMs = 1_000L;

    // ---- 阈值默认值（可被设备级配置逐字段覆盖） ----
    /** 默认过压阈值，V（220V +15%）。 */
    private double defaultOverVoltageV = 253.0;
    /** 默认欠压阈值，V（220V -6%）。 */
    private double defaultUnderVoltageV = 207.0;
    /** 默认过流阈值，A。 */
    private double defaultOverCurrentA = 16.0;
    /** 默认过功率阈值，W。 */
    private double defaultOverPowerW = 3680.0;
    /** 默认采样中断判定时长（最后一个采样点距今）。 */
    private long defaultInterruptionTimeoutMs = 300_000L;
    /** 默认连续越限次数才触发（去抖动）。 */
    private int defaultBreachTimes = 3;
    /** 默认连续恢复次数才恢复（去抖动）。 */
    private int defaultRecoverTimes = 3;

    public long getWindowMs() { return windowMs; }
    public void setWindowMs(long windowMs) { this.windowMs = windowMs; }
    public int getRetainWindows() { return retainWindows; }
    public void setRetainWindows(int retainWindows) { this.retainWindows = retainWindows; }
    public long getFutureSkewMs() { return futureSkewMs; }
    public void setFutureSkewMs(long futureSkewMs) { this.futureSkewMs = futureSkewMs; }
    public long getMaxAgeMs() { return maxAgeMs; }
    public void setMaxAgeMs(long maxAgeMs) { this.maxAgeMs = maxAgeMs; }
    public long getReorderLagMs() { return reorderLagMs; }
    public void setReorderLagMs(long reorderLagMs) { this.reorderLagMs = reorderLagMs; }
    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String adminToken) { this.adminToken = adminToken; }
    public long getTickIntervalMs() { return tickIntervalMs; }
    public void setTickIntervalMs(long tickIntervalMs) { this.tickIntervalMs = tickIntervalMs; }
    public double getDefaultOverVoltageV() { return defaultOverVoltageV; }
    public void setDefaultOverVoltageV(double defaultOverVoltageV) { this.defaultOverVoltageV = defaultOverVoltageV; }
    public double getDefaultUnderVoltageV() { return defaultUnderVoltageV; }
    public void setDefaultUnderVoltageV(double defaultUnderVoltageV) { this.defaultUnderVoltageV = defaultUnderVoltageV; }
    public double getDefaultOverCurrentA() { return defaultOverCurrentA; }
    public void setDefaultOverCurrentA(double defaultOverCurrentA) { this.defaultOverCurrentA = defaultOverCurrentA; }
    public double getDefaultOverPowerW() { return defaultOverPowerW; }
    public void setDefaultOverPowerW(double defaultOverPowerW) { this.defaultOverPowerW = defaultOverPowerW; }
    public long getDefaultInterruptionTimeoutMs() { return defaultInterruptionTimeoutMs; }
    public void setDefaultInterruptionTimeoutMs(long defaultInterruptionTimeoutMs) { this.defaultInterruptionTimeoutMs = defaultInterruptionTimeoutMs; }
    public int getDefaultBreachTimes() { return defaultBreachTimes; }
    public void setDefaultBreachTimes(int defaultBreachTimes) { this.defaultBreachTimes = defaultBreachTimes; }
    public int getDefaultRecoverTimes() { return defaultRecoverTimes; }
    public void setDefaultRecoverTimes(int defaultRecoverTimes) { this.defaultRecoverTimes = defaultRecoverTimes; }

    /** 启动时自检全局配置，非法配置直接失败而不是带病运行。 */
    public void validate() {
        requirePositive(windowMs, "window-ms");
        requirePositive(retainWindows, "retain-windows");
        requireNonNegative(futureSkewMs, "future-skew-ms");
        requirePositive(maxAgeMs, "max-age-ms");
        requirePositive(reorderLagMs, "reorder-lag-ms");
        requirePositive(tickIntervalMs, "tick-interval-ms");
        if (maxAgeMs < reorderLagMs) {
            throw new IllegalStateException("power.telemetry.max-age-ms must be >= reorder-lag-ms");
        }
        requirePositive(defaultOverVoltageV, "default-over-voltage-v");
        requirePositive(defaultUnderVoltageV, "default-under-voltage-v");
        requirePositive(defaultOverCurrentA, "default-over-current-a");
        requirePositive(defaultOverPowerW, "default-over-power-w");
        requirePositive(defaultInterruptionTimeoutMs, "default-interruption-timeout-ms");
        requirePositive(defaultBreachTimes, "default-breach-times");
        requirePositive(defaultRecoverTimes, "default-recover-times");
        if (defaultUnderVoltageV >= defaultOverVoltageV) {
            throw new IllegalStateException(
                    "default-under-voltage-v must be < default-over-voltage-v");
        }
    }

    private static void requirePositive(long v, String name) {
        if (v <= 0) {
            throw new IllegalStateException("power.telemetry." + name + " must be positive, got " + v);
        }
    }

    private static void requireNonNegative(long v, String name) {
        if (v < 0) {
            throw new IllegalStateException("power.telemetry." + name + " must be >= 0, got " + v);
        }
    }

    private static void requirePositive(double v, String name) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0d) {
            throw new IllegalStateException("power.telemetry." + name + " must be a positive number, got " + v);
        }
    }
}
