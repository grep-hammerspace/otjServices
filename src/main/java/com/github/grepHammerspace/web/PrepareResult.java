package com.github.grepHammerspace.web;

/**
 * Returned by {@link Driver#prepare} to describe what happened and what the caller
 * should show / do next.
 */
public record PrepareResult(Status status, int challengeNumber) {

    public enum Status {
        /** An existing Microsoft SSO session completed the whole login — no MFA needed. */
        LOGIN_COMPLETE,
        /** MFA push sent to Microsoft Authenticator — user just needs to tap "Approve". */
        MFA_PUSH_SENT,
        /** MFA push sent with number-matching — user must select {@link #challengeNumber()} in the app. */
        MFA_NUMBER_MATCH
    }

    public static PrepareResult loginComplete() {
        return new PrepareResult(Status.LOGIN_COMPLETE, -1);
    }

    public static PrepareResult mfaPushSent() {
        return new PrepareResult(Status.MFA_PUSH_SENT, -1);
    }

    public static PrepareResult mfaNumberMatch(int number) {
        return new PrepareResult(Status.MFA_NUMBER_MATCH, number);
    }

    public boolean requiresMfa() {
        return status != Status.LOGIN_COMPLETE;
    }

    /** Human-readable message suitable for displaying to the user. */
    public String userMessage() {
        return switch (status) {
            case LOGIN_COMPLETE   -> "Logged in via existing SSO session — no MFA required.";
            case MFA_PUSH_SENT    -> "Push notification sent to your Microsoft Authenticator app. Please approve it.";
            case MFA_NUMBER_MATCH -> "Push notification sent. In Microsoft Authenticator, select: " + challengeNumber;
        };
    }
}
