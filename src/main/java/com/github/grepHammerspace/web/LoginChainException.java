package com.github.grepHammerspace.web;

import java.io.IOException;

// A login-chain step failed in a way the driver itself detected. The message is composed only
// from SafeUrl-redacted URLs, Microsoft's page ids and result codes, so it is safe to log. Any
// other IOException's message is not: it can carry an upstream body.
public class LoginChainException extends IOException {
    public LoginChainException(String message) {
        super(message);
    }

    public LoginChainException(String message, Throwable cause) {
        super(message, cause);
    }
}
