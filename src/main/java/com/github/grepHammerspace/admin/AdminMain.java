package com.github.grepHammerspace.admin;

import com.github.grepHammerspace.ServerBootstrap;
import com.github.grepHammerspace.bind.AdminComponent;
import com.github.grepHammerspace.bind.DaggerAdminComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// No drivers, LLM client or sessions in this graph. Dagger providers are lazy, so it boots without
// ANTHROPIC_API_KEY.
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
