package com.github.grepHammerspace.web;

import javax.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** The Azure AD login flow, where the user approves a number match. Selects {@link AzureIdDriver}. */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface AzurePush {}
