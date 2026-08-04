package com.github.grepHammerspace.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Hashes and verifies the app's own login passwords with bcrypt.
 *
 * <p>Only the hash is ever stored — see {@link com.github.grepHammerspace.db.model.User#appPasswordHash()}.
 * These are this application's credentials, not OneAdvanced's, which never touch the database.
 */
@Singleton
public class PasswordHasher {
    /** 2^12 rounds — comfortably slow for brute force, imperceptible on a single login. */
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
