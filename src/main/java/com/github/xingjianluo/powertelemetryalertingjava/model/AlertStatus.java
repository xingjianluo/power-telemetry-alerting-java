package com.github.xingjianluo.powertelemetryalertingjava.model;

/**
 * 某类告警在状态机内的当前判定状态（含去抖计数）。
 */
public final class AlertStatus {
    private boolean active;
    private int violateCount;
    private int recoverCount;
    private long activeSinceSample;
    private long lastEventId;

    public AlertStatus() {
    }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getViolateCount() { return violateCount; }
    public void setViolateCount(int violateCount) { this.violateCount = violateCount; }
    public int getRecoverCount() { return recoverCount; }
    public void setRecoverCount(int recoverCount) { this.recoverCount = recoverCount; }
    public long getActiveSinceSample() { return activeSinceSample; }
    public void setActiveSinceSample(long activeSinceSample) { this.activeSinceSample = activeSinceSample; }
    public long getLastEventId() { return lastEventId; }
    public void setLastEventId(long lastEventId) { this.lastEventId = lastEventId; }
}
