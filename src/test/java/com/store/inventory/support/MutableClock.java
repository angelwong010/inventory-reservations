package com.store.inventory.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock that only moves when the test says so. */
public final class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-11-20T10:00:00Z");

    private Instant now;

    public MutableClock() {
        this(START);
    }

    public MutableClock(Instant now) {
        this.now = now;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
