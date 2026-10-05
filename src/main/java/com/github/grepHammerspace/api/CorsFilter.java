package com.github.grepHammerspace.api;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;

// Lets the app's web build, a PWA on Vercel, call this API from a browser. The native app sends no
// Origin header and is unaffected.
//
// Pre-matching so a preflight is answered before AuthenticationFilter runs: browsers send OPTIONS
// without the Authorization header, and a 401 there would block the real request. No cookies are
// involved, so there is no Allow-Credentials; the bearer token is the only credential.
@Provider
@PreMatching
public class CorsFilter implements ContainerRequestFilter, ContainerResponseFilter {
    // Exact origins, never a pattern: anyone can deploy to *.vercel.app.
    static final Set<String> ALLOWED_ORIGINS = Set.of(
        "https://otj-mobile-pwa.vercel.app",   // production
        "https://otj-log-preview.vercel.app",  // newest preview; the app's CI moves this alias
        "http://localhost:8082");              // expo start --web

    private static final String ALLOW_METHODS = "GET, POST, PUT, PATCH, DELETE";
    private static final String ALLOW_HEADERS = "Authorization, Content-Type";
    // Chrome's cap. Saves a preflight per call to the same URL within it.
    private static final String MAX_AGE_SECONDS = "7200";

    @Override
    public void filter(ContainerRequestContext request) {
        if (!isPreflight(request)) return;

        Response.ResponseBuilder preflight = Response.noContent();
        if (ALLOWED_ORIGINS.contains(request.getHeaderString("Origin"))) {
            preflight.header("Access-Control-Allow-Methods", ALLOW_METHODS)
                .header("Access-Control-Allow-Headers", ALLOW_HEADERS)
                .header("Access-Control-Max-Age", MAX_AGE_SECONDS);
        }
        // Any other origin gets a 204 with no grants, which the browser treats as a refusal.
        request.abortWith(preflight.build());
    }

    // Every response, including the preflight above and AuthenticationFilter's 401: the app has to
    // be able to read that one to notice an expired session.
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        MultivaluedMap<String, Object> headers = response.getHeaders();
        headers.add("Vary", "Origin");

        String origin = request.getHeaderString("Origin");
        if (origin != null && ALLOWED_ORIGINS.contains(origin)) {
            headers.putSingle("Access-Control-Allow-Origin", origin);
        }
    }

    private static boolean isPreflight(ContainerRequestContext request) {
        return HttpMethod.OPTIONS.equals(request.getMethod())
            && request.getHeaderString("Origin") != null
            && request.getHeaderString("Access-Control-Request-Method") != null;
    }
}
