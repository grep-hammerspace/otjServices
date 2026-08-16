package com.github.grepHammerspace.admin;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.security.Principal;

/**
 * Gates the admin API on the tailnet identity that {@code tailscale serve} injects.
 *
 * <p>Two things have to hold for a request to get through, and they are independent:
 *
 * <ol>
 *   <li><b>It arrived via {@code tailscale serve}</b> — evidenced by the
 *       {@code Tailscale-User-Login} header. The admin server binds {@code 127.0.0.1} only, so
 *       {@code tailscale serve} is the sole process that can reach it, which is the entire reason
 *       the header can be believed. <b>If that binding ever changes, this filter stops being a
 *       security control.</b> See {@code deploy/README.md}.</li>
 *   <li><b>That identity is allowlisted</b> — being on the tailnet is not the same as being an
 *       operator. Phones and laptops join the tailnet to <em>use</em> the app.</li>
 * </ol>
 *
 * <p>Both failures return the same 403 with the same body. A caller who is on the tailnet but not
 * an operator learns nothing about whether the allowlist exists or who is on it.
 */
@AdminIdentity
@Provider
@Priority(Priorities.AUTHENTICATION)
@Singleton
public class AdminIdentityFilter implements ContainerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminIdentityFilter.class);

    static final String IDENTITY_HEADER = "Tailscale-User-Login";
    private static final String FORBIDDEN = "{\"error\": \"Not an admin identity\"}";

    private final AdminAllowlist allowlist;

    @Inject
    public AdminIdentityFilter(AdminAllowlist allowlist) {
        this.allowlist = allowlist;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String login = requestContext.getHeaderString(IDENTITY_HEADER);

        if (login == null || login.isBlank()) {
            log.warn("Admin request to {} carried no {} header — rejected",
                    requestContext.getUriInfo().getPath(), IDENTITY_HEADER);
            abort(requestContext);
            return;
        }

        if (!allowlist.permits(login)) {
            log.warn("Admin request to {} from non-allowlisted login {} — rejected",
                    requestContext.getUriInfo().getPath(), login);
            abort(requestContext);
            return;
        }

        String principal = login.strip();
        SecurityContext original = requestContext.getSecurityContext();
        requestContext.setSecurityContext(new SecurityContext() {
            @Override public Principal getUserPrincipal() { return () -> principal; }
            @Override public boolean isUserInRole(String role) { return false; }
            @Override public boolean isSecure() { return original != null && original.isSecure(); }
            @Override public String getAuthenticationScheme() { return "Tailscale"; }
        });
    }

    private static void abort(ContainerRequestContext requestContext) {
        requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(FORBIDDEN)
                .build());
    }
}
