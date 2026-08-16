package com.github.grepHammerspace.stateStore;

/**
 * Which of the two login styles a prepared session is running.
 *
 * <p>The completes are not interchangeable. {@code POST /submit-with-mfa} hands a typed OTP to
 * {@link com.github.grepHammerspace.web.OtjDriver}, while {@code /azure-id/complete} waits on a
 * background poll that {@link com.github.grepHammerspace.web.AzureIdDriver} already started.
 * Calling the wrong one used to be silently accepted, because the driver slot was untyped:
 * {@code AzureIdDriver.completeMfa} ignores its token argument and would start a <em>second</em>
 * 120-second poll on the request thread, racing the first over the shared flow token.
 */
public enum LoginFlow {
    /** Keycloak, user types a TOTP into {@code /submit-with-mfa}. */
    KEYCLOAK_TOTP,
    /** Azure AD, user approves a number match; {@code /azure-id/complete} waits for the poll. */
    AZURE_PUSH
}
