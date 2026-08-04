package com.github.grepHammerspace.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hash_then_verify_roundTrips() {
        String hash = hasher.hash("correct horse battery staple");

        assertTrue(hasher.verify("correct horse battery staple", hash));
    }

    @Test
    void verify_wrongPassword_fails() {
        String hash = hasher.hash("right-password");

        assertFalse(hasher.verify("wrong-password", hash));
    }

    @Test
    void hash_isNotThePlaintext_andUsesBcryptFormat() {
        String hash = hasher.hash("secret");

        assertNotEquals("secret", hash);
        assertFalse(hash.contains("secret"));
        assertTrue(hash.startsWith("$2a$12$"), "expected bcrypt cost-12 format, got: " + hash);
    }

    @Test
    void hashing_samePassword_twice_producesDifferentHashes() {
        // each hash embeds a fresh salt
        assertNotEquals(hasher.hash("same"), hasher.hash("same"));
    }
}
