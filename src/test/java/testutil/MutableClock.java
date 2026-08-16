package testutil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} that stands still until a test advances it.
 *
 * <p>{@link Clock#fixed} covers the existing tests, which only ever need one instant — but it
 * cannot move, so it cannot express "the window slid". Anything testing an expiry or a sliding
 * window needs to step time forward without sleeping, which is what this provides.
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }
}
