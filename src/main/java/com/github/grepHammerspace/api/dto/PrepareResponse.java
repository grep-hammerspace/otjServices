package com.github.grepHammerspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a prepare call tells the client to do next.
 *
 * <p>{@code status} is the field to branch on:
 * <ul>
 *   <li>{@code login_complete} — an existing SSO session finished the login; go straight to the
 *       matching complete call.</li>
 *   <li>{@code otp_required} — Keycloak flow; the user types a code into {@code /submit-with-mfa}.</li>
 *   <li>{@code push_sent} — Azure flow; the user approves in Microsoft Authenticator, and
 *       {@code challengeNumber} is the number to tap when Microsoft asks for one.</li>
 * </ul>
 *
 * <p>{@code challengeNumber} is omitted rather than sent as null when there is no number match.
 */
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
