package com.github.grepHammerspace.web;

import javax.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** The Keycloak login flow, where the user types a TOTP. Selects {@link OtjDriver}. */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface Keycloak {}
