package com.github.grepHammerspace.api.dto;

/**
 * The user's OneAdvanced credentials — the JSON sealed inside a {@link SealedEnvelope}.
 *
 * <p>This is no longer a request body. Since the credentials were wrapped in a layer Cloudflare
 * has no key for, this record is what comes back out of
 * {@link com.github.grepHammerspace.crypto.CredentialKeyRing#open} and is parsed from the
 * decrypted bytes; nothing deserialises it straight off the wire any more.
 *
 * <p>This service still does not store them. The multi-user rollout removed the encrypted-at-rest
 * copy, so the only way to drive a login on the user's behalf is for the client to send them when
 * a submission is actually happening. They live as a local variable for the length of one request
 * and are handed straight to {@link com.github.grepHammerspace.web.Driver#prepare}.
 *
 * <p>{@code iat} is the epoch second the client sealed the envelope, and it is inside the
 * ciphertext rather than beside it precisely so it cannot be edited by anything on the path.
 * {@link com.github.grepHammerspace.crypto.CredentialKeyRing#checkFreshness} bounds it to a few
 * minutes either side of now — that is not a replay defence (anything holding the bearer token can
 * replay the whole request) but it does stop a captured envelope from being useful indefinitely.
 *
 * <p>Do not confuse these with {@code User.appUsername} / {@code appPasswordHash}, which are
 * this service's own login and are bcrypt-hashed.
 *
 * <p>The {@code toString()} override is load-bearing rather than cosmetic. A record generates
 * one that prints every component, so any {@code log.info("...{}", body)} — or a record nested
 * in a collection that gets logged — would put the password in the log. Overriding it means the
 * careless call site is harmless instead of a breach.
 */
public record OneAdvancedCredentials(String username, String password, Long iat) {

    /** For tests and call sites that have no envelope behind them. */
    public OneAdvancedCredentials(String username, String password) {
        this(username, password, null);
    }

    @Override
    public String toString() {
        return "OneAdvancedCredentials[username=<redacted>, password=<redacted>]";
    }
}
