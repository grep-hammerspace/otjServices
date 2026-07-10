package com.github.grepHammerspace.tailscale;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * Helper class to extract the tailscale identity of someone who makes a request to the server.
 */
public class TailscaleIdentityHelper {
    private static final String IDENTITY_HEADER = "Tailscale-User-Login";

    /**
     * Reads the identity injected by the host's {@code tailscale serve} process. Trustworthy only
     * because the app is reachable exclusively via 127.0.0.1 — tailscale serve is the sole process
     * that can reach that loopback port and set this header.
     *
     * @throws IOException if the header is missing or blank, meaning the request did not arrive via tailscale serve
     */
    public static String getUser(HttpServletRequest request) throws IOException {
        String user = request.getHeader(IDENTITY_HEADER);
        if (user == null || user.isBlank()) {
            throw new IOException(
                    "Missing " + IDENTITY_HEADER + " header — request did not arrive via tailscale serve");
        }
        return user;
    }
}
