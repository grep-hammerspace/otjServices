package com.github.grepHammerspace.admin;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

// Not on /health: the container's own health check has no identity header to present.
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminIdentity {
}
