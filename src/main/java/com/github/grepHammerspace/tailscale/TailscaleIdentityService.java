package com.github.grepHammerspace.tailscale;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

@FunctionalInterface
public interface TailscaleIdentityService {
    String getUser(HttpServletRequest request) throws IOException;
}
