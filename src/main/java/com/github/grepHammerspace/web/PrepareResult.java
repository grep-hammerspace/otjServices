package com.github.grepHammerspace.web;

// challengeNumber is set only for a Microsoft number match.
public record PrepareResult(Status status, Integer challengeNumber) {
    public enum Status {
        LOGGED_IN,
        OTP_REQUIRED,
        PUSH_SENT
    }

    public static PrepareResult loggedIn() {
        return new PrepareResult(Status.LOGGED_IN, null);
    }

    public static PrepareResult otpRequired() {
        return new PrepareResult(Status.OTP_REQUIRED, null);
    }

    public static PrepareResult pushSent(Integer challengeNumber) {
        return new PrepareResult(Status.PUSH_SENT, challengeNumber);
    }
}
