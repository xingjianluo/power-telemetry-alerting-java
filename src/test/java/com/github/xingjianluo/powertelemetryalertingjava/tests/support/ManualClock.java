package com.github.xingjianluo.powertelemetryalertingjava.tests.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/** 测试用可控时钟，默认从固定时刻起步，保证测试可复现。 */
public class ManualClock extends Clock {

    private final AtomicLong nowMs;

    public ManualClock() {
        this(1_700_000_000_000L);
    }

    public ManualClock(long startMs) {
        this.nowMs = new AtomicLong(startMs);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(nowMs.get());
    }

    @Override
    public long millis() {
        return nowMs.get();
    }

    public void setMillis(long ms) {
        nowMs.set(ms);
    }

    public void advance(long deltaMs) {
        nowMs.addAndGet(deltaMs);
    }
}
