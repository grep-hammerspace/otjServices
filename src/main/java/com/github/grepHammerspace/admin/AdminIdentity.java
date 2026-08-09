package com.github.grepHammerspace.admin;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Binds {@link AdminIdentityFilter} to a resource, so only requests carrying an allowlisted
 * tailnet identity reach it.
 *
 * <p>Deliberately not applied to {@code /health}: the container's own health check curls
 * loopback from inside the container and has no identity header to present.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminIdentity {
}
