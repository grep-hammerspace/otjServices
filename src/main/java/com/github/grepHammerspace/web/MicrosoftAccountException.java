package com.github.grepHammerspace.web;

// QMUL's Keycloak handed the username to Microsoft: the account signs in through QMUL's Azure AD, so
// there is no OneAdvanced code to type and the other route is the one that works. The message is
// fixed, so it is safe to log.
public class MicrosoftAccountException extends LoginChainException {
    public MicrosoftAccountException() {
        super("QMUL's Keycloak sent this account to Microsoft — it signs in with the Azure route");
    }
}
