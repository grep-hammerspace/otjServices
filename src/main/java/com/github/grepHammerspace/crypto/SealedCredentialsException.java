package com.github.grepHammerspace.crypto;

public class SealedCredentialsException extends Exception {

    public enum Reason {
        MALFORMED_ENVELOPE("malformed_envelope",
                "The encrypted credentials could not be read. Update the app and try again."),
        UNSUPPORTED_VERSION("unsupported_version",
                "This app is sending an encryption format this server does not support. Update the app."),
        UNKNOWN_KEY("unknown_key",
                "The server's encryption key has changed. Try again."),
        UNDECRYPTABLE("undecryptable",
                "The encrypted credentials could not be decrypted. Try again."),
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
        // No cause chained: never quote any part of the envelope.
        super(reason.code());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
