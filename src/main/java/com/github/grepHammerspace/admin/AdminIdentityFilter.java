package com.github.grepHammerspace.admin;

import com.github.grepHammerspace.api.dto.ApiError;
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

// The header can be believed only because the admin server binds 127.0.0.1 and tailscale serve is
// the sole process that reaches it. Widen that binding and this filter stops being a security
// control.
//
// Both failures return the same 403, so a tailnet user who isn't an operator learns nothing about
// the allowlist.
@AdminIdentity
@Provider
@Priority(Priorities.AUTHENTICATION)
@Singleton
public class AdminIdentityFilter implements ContainerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AdminIdentityFilter.class);

    static final String IDENTITY_HEADER = "Tailscale-User-Login";
    private static final ApiError FORBIDDEN = new ApiError("Not an admin identity");

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
