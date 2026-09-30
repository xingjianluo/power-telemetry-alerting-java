package com.github.xingjianluo.powertelemetryalertingjava.model;

/** 告警触发/恢复事件，全程留痕、可追溯。 */
public class AlertEvent {
    private String eventId;
    private String deviceId;
    private AlertType type;
    private boolean triggered;
    private long occurredAtMs;
    private double voltageV;
    private double currentA;
    private double powerW;
    private String basis;

    public AlertEvent() {}

    public AlertEvent(String eventId, String deviceId, AlertType type, boolean triggered,
                      long occurredAtMs, double voltageV, double currentA, double powerW, String basis) {
        this.eventId = eventId;
        this.deviceId = deviceId;
        this.type = type;
        this.triggered = triggered;
        this.occurredAtMs = occurredAtMs;
        this.voltageV = voltageV;
        this.currentA = currentA;
        this.powerW = powerW;
        this.basis = basis;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public AlertType getType() { return type; }
    public void setType(AlertType type) { this.type = type; }
    public boolean isTriggered() { return triggered; }
    public void setTriggered(boolean triggered) { this.triggered = triggered; }
    public long getOccurredAtMs() { return occurredAtMs; }
    public void setOccurredAtMs(long occurredAtMs) { this.occurredAtMs = occurredAtMs; }
    public double getVoltageV() { return voltageV; }
    public void setVoltageV(double voltageV) { this.voltageV = voltageV; }
    public double getCurrentA() { return currentA; }
    public void setCurrentA(double currentA) { this.currentA = currentA; }
    public double getPowerW() { return powerW; }
    public void setPowerW(double powerW) { this.powerW = powerW; }
    public String getBasis() { return basis; }
    public void setBasis(String basis) { this.basis = basis; }
}
