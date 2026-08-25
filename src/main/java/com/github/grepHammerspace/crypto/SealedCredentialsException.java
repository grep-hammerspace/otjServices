package com.github.grepHammerspace.crypto;

/**
 * Why an envelope could not be opened, in the five cases a caller can tell apart.
 *
 * <p>Carries a {@link Reason} rather than a message: the resource turns the reason into the wire
 * code and the user-facing sentence, and the exception itself never quotes any part of the
 * envelope. A decryption failure that echoed what it had been given would be a way to probe the
 * key from outside.
 */
public class SealedCredentialsException extends Exception {

    public enum Reason {
        /** Missing field, wrong length, or not base64url. */
        MALFORMED_ENVELOPE("malformed_envelope",
                "The encrypted credentials could not be read. Update the app and try again."),
        /** A format version this build does not implement. */
        UNSUPPORTED_VERSION("unsupported_version",
                "This app is sending an encryption format this server does not support. Update the app."),
        /** Sealed to a key this server no longer holds — nearly always a restart. */
        UNKNOWN_KEY("unknown_key",
                "The server's encryption key has changed. Try again."),
        /** The tag did not verify: wrong key, tampering, or corruption. */
        UNDECRYPTABLE("undecryptable",
                "The encrypted credentials could not be decrypted. Try again."),
        /** Opened fine, but was sealed too long ago to be a live request. */
        STALE_ENVELOPE("stale_envelope",
                "That request was prepared too long ago. Try again.");

        private final String code;
        private final String message;

        Reason(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String code() { return code; }
        public String message() { return message; }
    }

    private final Reason reason;

    public SealedCredentialsException(Reason reason) {
        // No cause chained on purpose. The causes here are GeneralSecurityExceptions whose
        // messages name key sizes and algorithms; the resource logs the reason and nothing else.
        super(reason.code());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
