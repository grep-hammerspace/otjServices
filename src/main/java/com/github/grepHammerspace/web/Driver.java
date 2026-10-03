package com.github.grepHammerspace.web;

import java.io.IOException;

// Stateful and single-use: prepare, completeMfa on a later request, then submit. Credentials stay
// out of logs, exceptions and fields (AzureIdDriver's username is the one documented exception).
public interface Driver {
    PrepareResult prepare(String username, String password) throws IOException;

    void completeMfa(String mfaToken) throws IOException;

    // learnerId is read from the account at submit time and overrides each row's stored copy, so a
    // correction reaches rows already queued.
    OtjSubmitResult submitPendingOtjs(String userId, String learnerId);
}
