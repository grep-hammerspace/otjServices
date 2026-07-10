package com.github.grepHammerspace.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts/decrypts user passwords at rest with AES-256-GCM.
 *
 * <p>Passwords must be recoverable (not just verifiable) because they are replayed into
 * the OneAdvanced login flow via Selenium, which rules out one-way hashing.
 */
@Singleton
public class PasswordCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ENC_PREFIX = "ENC:";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;

    @Inject
    public PasswordCipher() {
        String envKey = System.getenv("PASSWORD_ENCRYPTION_KEY");
        if (envKey == null || envKey.isBlank()) {
            throw new IllegalStateException("PASSWORD_ENCRYPTION_KEY environment variable is not set");
        }
        this.key = init(envKey);
    }

    /** Used by tests to supply a fixed, deterministic key. */
    public PasswordCipher(String base64Key) {
        this.key = init(base64Key);
    }

    private static SecretKeySpec init(String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        return new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LENGTH_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH_BYTES, ciphertext.length);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to encrypt password", e);
        }
    }

    /** Old plaintext rows (no {@code ENC:} prefix) pass through unchanged. */
    public String decrypt(String stored) {
        if (!stored.startsWith(ENC_PREFIX)) {
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decrypt password", e);
        }
    }
}
