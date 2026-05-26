package com.github.grepHammerspace.tailscale;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/** Resolves the Tailscale login name of the client who made an incoming HTTP request. */
@FunctionalInterface
public interface TailscaleIdentityService {
    String getUser(HttpServletRequest request) throws IOException;
}
