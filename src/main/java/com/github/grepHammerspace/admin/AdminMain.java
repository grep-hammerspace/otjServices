package com.github.grepHammerspace.admin;

import com.github.grepHammerspace.ServerBootstrap;
import com.github.grepHammerspace.bind.AdminComponent;
import com.github.grepHammerspace.bind.DaggerAdminComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the admin API — a second server, in a second container, from the same image
 * as {@link com.github.grepHammerspace.Main}.
 *
 * <p>It is a separate process rather than a path on the main API because the main API is destined
 * to sit behind a public domain (step 09 of the auth plan), and a path is one proxy rule away from
 * being internet-facing. This binds a port that is published to loopback only and never proxied
 * anywhere but the tailnet.
 *
 * <p>The graph it builds is deliberately small: no browser drivers, no LLM client, no session
 * handling. Dagger instantiates providers lazily, so the admin container does not need
 * {@code ANTHROPIC_API_KEY} to boot even though {@code AppModule} declares a binding for it.
 */
public class AdminMain {
    private static final Logger log = LoggerFactory.getLogger(AdminMain.class);

    static final int DEFAULT_PORT = 8946;

    public static void main(String[] args) throws Exception {
        AdminComponent component = DaggerAdminComponent.create();

        int port = port();
        ServerBootstrap.start(port, component.adminInviteResource(), component.adminIdentityFilter());

        log.info("Admin API started at http://0.0.0.0:{} — expose it only via tailscale serve", port);
        Thread.currentThread().join();
    }

    private static int port() {
        String configured = System.getenv("ADMIN_PORT");
        if (configured == null || configured.isBlank()) return DEFAULT_PORT;
        return Integer.parseInt(configured.strip());
    }
}
