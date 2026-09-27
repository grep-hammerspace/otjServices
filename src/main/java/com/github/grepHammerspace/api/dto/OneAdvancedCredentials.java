package com.github.grepHammerspace.api.dto;

// toString() is overridden so that logging this record can't leak the password.
public record OneAdvancedCredentials(String username, String password) {
    @Override
    public String toString() {
        return "OneAdvancedCredentials[username=<redacted>, password=<redacted>]";
    }
}
