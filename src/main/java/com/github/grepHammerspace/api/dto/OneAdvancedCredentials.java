package com.github.grepHammerspace.api.dto;

/**
 * The user's OneAdvanced credentials, supplied on each prepare call.
 *
 * <p>This service does not store them. The multi-user rollout removed the encrypted-at-rest
 * copy, so the only way to drive a login on the user's behalf is for the client to send them
 * when a submission is actually happening. They live as a local variable for the length of one
 * request and are handed straight to {@link com.github.grepHammerspace.web.Driver#prepare}.
 *
 * <p>Do not confuse these with {@code User.appUsername} / {@code appPasswordHash}, which are
 * this service's own login and are bcrypt-hashed.
 *
 * <p>The {@code toString()} override is load-bearing rather than cosmetic. A record generates
 * one that prints every component, so any {@code log.info("...{}", body)} — or a record nested
 * in a collection that gets logged — would put the password in the log. Overriding it means the
 * careless call site is harmless instead of a breach.
 */
public record OneAdvancedCredentials(String username, String password) {

    @Override
    public String toString() {
        return "OneAdvancedCredentials[username=<redacted>, password=<redacted>]";
    }
}
