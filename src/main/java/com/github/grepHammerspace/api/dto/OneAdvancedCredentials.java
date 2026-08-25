package com.github.grepHammerspace.api.dto;

// Sealed inside a SealedEnvelope; iat is inside the ciphertext so nothing on the path can edit it.
// toString() is overridden so that logging this record can't leak the password.
public record OneAdvancedCredentials(String username, String password, Long iat) {

    public OneAdvancedCredentials(String username, String password) {
        this(username, password, null);
    }

    @Override
    public String toString() {
        return "OneAdvancedCredentials[username=<redacted>, password=<redacted>]";
    }
}
