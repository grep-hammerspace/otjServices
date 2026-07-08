package com.github.grepHammerspace.tailscale;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * Resolves the caller's Tailscale identity from the {@code Tailscale-User-Login} header
 * injected by {@code tailscale serve} when it reverse-proxies a request to this app.
 *
 * SECURITY INVARIANT: this header is only trustworthy because the app is reachable
 * exclusively on 127.0.0.1 — the only process that can reach it is the host's
 * {@code tailscale serve}, which is what actually sets this header. If the bind address
 * or network topology ever changes such that an untrusted caller could reach the app
 * directly, this header can be spoofed. Do not relax the loopback-only binding without
 * re-examining this assumption.
 */
public class TailscaleIdentityHelper {
    // Verify exact header name/casing against current Tailscale `tailscale serve` docs.
    private static final String IDENTITY_HEADER = "Tailscale-User-Login";

    public static String getUser(HttpServletRequest request) throws IOException {
        String user = request.getHeader(IDENTITY_HEADER);
        if (user == null || user.isBlank()) {
            throw new SecurityException(
                    "Missing " + IDENTITY_HEADER + " header — request did not arrive via tailscale serve");
        }
        return user;
    }
}
