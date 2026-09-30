package com.github.xingjianluo.powertelemetryalertingjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务参数，均可通过 application.properties / 环境变量覆盖。
 */
@ConfigurationProperties(prefix = "power.service")
public class ServiceProperties {

    /** 窗口长度（毫秒），默认 60s。 */
    private long windowMs = 60_000;

    /** 允许的最大历史时间范围（毫秒），默认 24h。 */
    private long maxHistoryMs = 24L * 60 * 60 * 1000;

    /** 允许的未来时钟偏差（毫秒），默认 5s。 */
    private long futureToleranceMs = 5_000;

    /** 允许的乱序迟到时间（毫秒）；早于水位线超过该值视为时间回退，默认 5min。 */
    private long allowedLatenessMs = 5L * 60 * 1000;

    /** 窗口封存后仍保留在内存的窗口数（按窗口起始时间）。 */
    private int retainedWindows = 2880;

    /** 采样中断墙钟扫描周期（毫秒），默认 1s。 */
    private long interruptionSweepMs = 1_000;

    /** 连续多少次违规触发告警（去抖），默认 3。 */
    private int triggerConsecutive = 3;

    /** 连续多少次恢复解除告警（去抖），默认 3。 */
    private int recoverConsecutive = 3;

    /** 全局默认过压阈值（V），设备未单独设置时回落至此。 */
    private Double defaultOverVoltage = 253.0;

    /** 全局默认欠压阈值（V）。 */
    private Double defaultUnderVoltage = 198.0;

    /** 全局默认过流阈值（A）。 */
    private Double defaultOverCurrent = 32.0;

    /** 全局默认过功率阈值（W）。 */
    private Double defaultOverPower = 7_000.0;

    /** 全局默认最大采样间隔（毫秒），默认 30s。 */
    private Long defaultMaxSampleGap = 30_000L;

    /** 管理员令牌（注册设备、改任意配置需要）。 */
    private String adminToken = "";

    /** 快照文件路径；为空则使用工作目录 ./data/snapshot.json。 */
    private String snapshotFile = "data/snapshot.json";

    /** 是否在每次接入批次后同步落盘（测试与单机默认开启）。 */
    private boolean snapshotAfterEachBatch = true;

    /** 鉴权开关；关闭时接口无需令牌（仅限本地验证）。 */
    private boolean authEnabled = false;

    public long getWindowMs() { return windowMs; }
    public void setWindowMs(long windowMs) { this.windowMs = windowMs; }
    public long getMaxHistoryMs() { return maxHistoryMs; }
    public void setMaxHistoryMs(long maxHistoryMs) { this.maxHistoryMs = maxHistoryMs; }
    public long getFutureToleranceMs() { return futureToleranceMs; }
    public void setFutureToleranceMs(long futureToleranceMs) { this.futureToleranceMs = futureToleranceMs; }
    public long getAllowedLatenessMs() { return allowedLatenessMs; }
    public void setAllowedLatenessMs(long allowedLatenessMs) { this.allowedLatenessMs = allowedLatenessMs; }
    public int getRetainedWindows() { return retainedWindows; }
    public void setRetainedWindows(int retainedWindows) { this.retainedWindows = retainedWindows; }
    public long getInterruptionSweepMs() { return interruptionSweepMs; }
    public void setInterruptionSweepMs(long interruptionSweepMs) { this.interruptionSweepMs = interruptionSweepMs; }
    public int getTriggerConsecutive() { return triggerConsecutive; }
    public void setTriggerConsecutive(int triggerConsecutive) { this.triggerConsecutive = triggerConsecutive; }
    public int getRecoverConsecutive() { return recoverConsecutive; }
    public void setRecoverConsecutive(int recoverConsecutive) { this.recoverConsecutive = recoverConsecutive; }
    public Double getDefaultOverVoltage() { return defaultOverVoltage; }
    public void setDefaultOverVoltage(Double v) { this.defaultOverVoltage = v; }
    public Double getDefaultUnderVoltage() { return defaultUnderVoltage; }
    public void setDefaultUnderVoltage(Double v) { this.defaultUnderVoltage = v; }
    public Double getDefaultOverCurrent() { return defaultOverCurrent; }
    public void setDefaultOverCurrent(Double v) { this.defaultOverCurrent = v; }
    public Double getDefaultOverPower() { return defaultOverPower; }
    public void setDefaultOverPower(Double v) { this.defaultOverPower = v; }
    public Long getDefaultMaxSampleGap() { return defaultMaxSampleGap; }
    public void setDefaultMaxSampleGap(Long v) { this.defaultMaxSampleGap = v; }
    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String adminToken) { this.adminToken = adminToken; }
    public String getSnapshotFile() { return snapshotFile; }
    public void setSnapshotFile(String snapshotFile) { this.snapshotFile = snapshotFile; }
    public boolean isSnapshotAfterEachBatch() { return snapshotAfterEachBatch; }
    public void setSnapshotAfterEachBatch(boolean snapshotAfterEachBatch) { this.snapshotAfterEachBatch = snapshotAfterEachBatch; }
    public boolean isAuthEnabled() { return authEnabled; }
    public void setAuthEnabled(boolean authEnabled) { this.authEnabled = authEnabled; }
}
