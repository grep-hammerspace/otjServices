package com.github.grepHammerspace.stateStore;

// The two completes are not interchangeable: AzureIdDriver.completeMfa ignores its token and would
// start a second Microsoft poll.
public enum LoginFlow {
    KEYCLOAK_TOTP,
    AZURE_PUSH
}
