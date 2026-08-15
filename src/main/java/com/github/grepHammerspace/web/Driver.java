package com.github.grepHammerspace.web;

import java.io.IOException;

/**
 * A login-and-submit session against OneAdvanced's cloud-education platform.
 *
 * <p>Implementations are <b>stateful and single-use</b>: {@code prepare} opens a session and
 * parks the cookies and flow tokens on the instance, {@code completeMfa} finishes the login on a
 * later HTTP request, and {@code submitPendingOtjs} spends the resulting session. The instance
 * therefore has to survive between requests — see
 * {@link com.github.grepHammerspace.stateStore.UserStateStore}.
 *
 * <p><b>Credentials.</b> The username and password belong to the user's OneAdvanced account, and
 * arrive on the request rather than from storage — this service does not hold them. Implementations
 * must keep them out of logs, exception messages, and fields; see
 * {@link AzureIdDriver} for the single documented exception.
 */
public interface Driver {
    PrepareResult prepare(String username, String password) throws IOException;

    void completeMfa(String mfaToken) throws IOException;

    /**
     * Posts every unposted activity log for {@code userId} to OneAdvanced.
     *
     * @param learnerId the learner ID to post under, read from the user's account at submit time.
     *                  It deliberately overrides the {@code learnerId} stamped on each
     *                  {@link com.github.grepHammerspace.db.model.ActivityLog} when the row was
     *                  written, so that correcting a typo via {@code PATCH /auth/me} also fixes
     *                  rows that are already queued. The stored value on each row is left alone
     *                  as a record of what was intended at the time.
     */
    OtjSubmitResult submitPendingOtjs(String userId, String learnerId);
}
