package com.github.grepHammerspace.stateStore;

import com.github.grepHammerspace.web.Driver;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * One prepared, not-yet-spent login: the driver holding the live session, the flow it belongs to,
 * and the background login future for the Azure push style.
 *
 * <p>A single immutable value rather than separate mutable slots, so that a re-prepare swaps
 * everything at once. The previous shape had two independent {@code volatile} fields, which left
 * a window where {@code /azure-id/complete} could read the driver from one prepare and the future
 * from the one before it.
 *
 * @param future completed when the login finishes. For {@link LoginFlow#AZURE_PUSH} a virtual
 *               thread completes it after Microsoft reports approval; for
 *               {@link LoginFlow#KEYCLOAK_TOTP} it is already complete, because that flow does
 *               not finish until the user posts an OTP.
 */
public record LoginSession(LoginFlow flow,
                           Driver driver,
                           CompletableFuture<Void> future,
                           Instant startedAt) {

    /**
     * How long a prepared session stays usable.
     *
     * <p>Generous next to what it covers: the Azure poll budget is 40 × 3 s = 120 s and the
     * complete endpoint waits 125 s. Past that the session is abandoned, and it is holding live
     * OneAdvanced and Microsoft cookies plus the user's OneAdvanced username. Before this,
     * sessions were never evicted and accumulated for the life of the process.
     */
    public static final Duration TTL = Duration.ofMinutes(5);

    public boolean isExpired(Instant now) {
        return startedAt.plus(TTL).isBefore(now);
    }
}
