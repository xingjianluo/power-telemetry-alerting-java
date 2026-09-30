package com.github.xingjianluo.powertelemetryalertingjava.model;

/**
 * 单台设备、单个告警类型的状态机运行时。
 * streak 为当前连续越限（正）或连续恢复（负）计数，用于抖动抑制。
 */
public class AlertRuntime {
    private AlertType type;
    private boolean active;
    private int streak;
    private long activeSinceMs;
    private Long lastChangeMs;
    private long lastEvaluatedMs;

    public AlertRuntime() {}

    public AlertRuntime(AlertType type) {
        this.type = type;
    }

    public AlertType getType() { return type; }
    public void setType(AlertType type) { this.type = type; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getStreak() { return streak; }
    public void setStreak(int streak) { this.streak = streak; }
    public long getActiveSinceMs() { return activeSinceMs; }
    public void setActiveSinceMs(long activeSinceMs) { this.activeSinceMs = activeSinceMs; }
    public Long getLastChangeMs() { return lastChangeMs; }
    public void setLastChangeMs(Long lastChangeMs) { this.lastChangeMs = lastChangeMs; }
    public long getLastEvaluatedMs() { return lastEvaluatedMs; }
    public void setLastEvaluatedMs(long lastEvaluatedMs) { this.lastEvaluatedMs = lastEvaluatedMs; }
}
