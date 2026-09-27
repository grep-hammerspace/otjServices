package com.github.grepHammerspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrepareResponse(String status, String message, Integer challengeNumber) {
    public static PrepareResponse loginComplete(String message) {
        return new PrepareResponse("login_complete", message, null);
    }

    public static PrepareResponse otpRequired(String message) {
        return new PrepareResponse("otp_required", message, null);
    }

    public static PrepareResponse pushSent(String message, Integer challengeNumber) {
        return new PrepareResponse("push_sent", message, challengeNumber);
    }
}
