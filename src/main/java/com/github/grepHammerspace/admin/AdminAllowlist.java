package com.github.grepHammerspace.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

// Fails closed: an unset or empty ADMIN_ALLOWED_LOGINS permits nobody.
public final class AdminAllowlist {
    private static final Logger log = LoggerFactory.getLogger(AdminAllowlist.class);

    static final String ENV_VAR = "ADMIN_ALLOWED_LOGINS";

    private final Set<String> logins;

    AdminAllowlist(Set<String> logins) {
        this.logins = logins;
    }

    public static AdminAllowlist parse(String raw) {
        if (raw == null || raw.isBlank()) return new AdminAllowlist(Set.of());
        return new AdminAllowlist(Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .map(entry -> entry.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet()));
    }

    public static AdminAllowlist fromEnv() {
        AdminAllowlist allowlist = parse(System.getenv(ENV_VAR));
        if (allowlist.isEmpty()) {
            log.error("{} is unset or empty — every admin request will be rejected with 403", ENV_VAR);
        } else {
            log.info("Admin API allowlist loaded with {} login(s)", allowlist.size());
        }
        return allowlist;
    }

    public boolean permits(String login) {
        return login != null && logins.contains(login.strip().toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return logins.isEmpty();
    }

    public int size() {
        return logins.size();
    }
}
