package com.github.grepHammerspace.web;

import java.io.IOException;

public interface Driver {
    PrepareResult prepare(String username, String password) throws IOException;
    void completeMfa(String mfaToken) throws IOException;
    OtjSubmitResult submitPendingOtjs(String userId);
}
