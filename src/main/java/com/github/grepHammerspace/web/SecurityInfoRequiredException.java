package com.github.grepHammerspace.web;

// Microsoft stopped the login at its "proof-up" interrupt (pgid=ConvergedProofUpRedirect): the
// account's organisation wants security info added or confirmed at mysignins.microsoft.com before
// it lets the user in. The credentials and the push approval were fine, so this must not be
// reported as a failed login. The message is fixed, so it is safe to log.
public class SecurityInfoRequiredException extends LoginChainException {
    public SecurityInfoRequiredException() {
        super("Microsoft requires security info registration (pgid=ConvergedProofUpRedirect)");
    }
}
