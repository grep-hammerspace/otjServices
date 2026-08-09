package com.github.grepHammerspace.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The set of tailnet logins permitted to use the admin API.
 *
 * <p>Fails closed: an unset or empty {@code ADMIN_ALLOWED_LOGINS} permits nobody. A misconfigured
 * deploy therefore locks the operator out rather than opening the API to every device on the
 * tailnet, and the startup log says so plainly.
 *
 * <p>Comparison is case-insensitive because the header carries an email-style login and mail
 * addresses are not case-sensitive in practice.
 */
public final class AdminAllowlist {

    private static final Logger log = LoggerFactory.getLogger(AdminAllowlist.class);

    static final String ENV_VAR = "ADMIN_ALLOWED_LOGINS";

    private final Set<String> logins;

    AdminAllowlist(Set<String> logins) {
        this.logins = logins;
    }

    /** Parses a comma-separated list, ignoring surrounding whitespace and blank entries. */
    public static AdminAllowlist parse(String raw) {
        if (raw == null || raw.isBlank()) return new AdminAllowlist(Set.of());
        return new AdminAllowlist(Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .map(entry -> entry.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet()));
    }

    /** Reads {@value #ENV_VAR}, warning loudly if it leaves the API unusable. */
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
