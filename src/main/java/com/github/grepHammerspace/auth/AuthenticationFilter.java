package com.github.grepHammerspace.auth;

import com.github.grepHammerspace.api.dto.ApiError;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.security.Principal;
import java.util.Optional;

@Authenticated
@Provider
@Priority(Priorities.AUTHENTICATION)
@Singleton
public class AuthenticationFilter implements ContainerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final ApiError INVALID_TOKEN = new ApiError("Missing or invalid bearer token");

    private final SessionTokenService sessionTokenService;

    @Inject
    public AuthenticationFilter(SessionTokenService sessionTokenService) {
        this.sessionTokenService = sessionTokenService;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String header = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            abort(requestContext);
            return;
        }

        Optional<String> userId = sessionTokenService.resolve(header.substring(BEARER_PREFIX.length()).strip());
        if (userId.isEmpty()) {
            abort(requestContext);
            return;
        }

        log.info("{} /{} from user {}", requestContext.getMethod(),
                requestContext.getUriInfo().getPath(), userId.get());

        SecurityContext original = requestContext.getSecurityContext();
        requestContext.setSecurityContext(new SecurityContext() {
            @Override public Principal getUserPrincipal() { return userId::get; }
            @Override public boolean isUserInRole(String role) { return false; }
            @Override public boolean isSecure() { return original != null && original.isSecure(); }
            @Override public String getAuthenticationScheme() { return "Bearer"; }
        });
    }

    private static void abort(ContainerRequestContext requestContext) {
        requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(INVALID_TOKEN)
                .build());
    }
}
