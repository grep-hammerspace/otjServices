package com.github.grepHammerspace.auth;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Name-binding annotation marking JAX-RS resources that require a valid bearer token.
 *
 * <p>Resources carrying this annotation are intercepted by {@link AuthenticationFilter};
 * resources without it (health, auth) stay anonymous by construction rather than via an
 * exemption list that can drift.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Authenticated {
}
