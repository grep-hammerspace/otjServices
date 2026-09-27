package com.github.grepHammerspace.web;

public record PrepareResult(Status status, int challengeNumber) {
    public enum Status {
        LOGIN_COMPLETE,
        MFA_PUSH_SENT,
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

    public String userMessage() {
        return switch (status) {
            case LOGIN_COMPLETE   -> "Logged in via existing SSO session — no MFA required.";
            case MFA_PUSH_SENT    -> "Push notification sent to your Microsoft Authenticator app. Please approve it.";
            case MFA_NUMBER_MATCH -> "Push notification sent. In Microsoft Authenticator, select: " + challengeNumber;
        };
    }
}
