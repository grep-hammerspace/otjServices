package com.github.grepHammerspace.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class PasswordHasher {
    private static final int COST = 12;

    @Inject
    public PasswordHasher() {
    }

    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(COST, password.toCharArray());
    }

    public boolean verify(String password, String hash) {
        return BCrypt.verifyer().verify(password.toCharArray(), hash.toCharArray()).verified;
    }
}
