package com.github.grepHammerspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.grepHammerspace.web.PrepareResult;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrepareResponse(String status, String message, Integer challengeNumber) {
    public static PrepareResponse from(PrepareResult result) {
        Integer number = result.challengeNumber();
        return switch (result.status()) {
            case LOGGED_IN -> new PrepareResponse("login_complete",
                    "Logged in via existing SSO session — no MFA required.", null);
            case OTP_REQUIRED -> new PrepareResponse("otp_required",
                    "Enter the current code from your authenticator app.", null);
            case PUSH_SENT -> new PrepareResponse("push_sent", number == null
                    ? "Push notification sent to your Microsoft Authenticator app. Please approve it."
                    : "Push notification sent. In Microsoft Authenticator, select: " + number, number);
        };
    }
}
