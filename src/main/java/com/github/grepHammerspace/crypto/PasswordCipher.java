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
 * the OneAdvanced login flow (see {@code SmartAssessorDriver}), which rules out one-way hashing.
 *
 * <p>Stored format is {@code ENC:1:<base64(iv || ciphertext)>}. The version segment leaves
 * room for future key rotation. The ciphertext is authenticated against the owning user's
 * id as GCM associated data, so a ciphertext copied onto another user's row fails to
 * decrypt. Anything not in this format is rejected — plaintext values are never passed
 * through.
 */
@Singleton
public class PasswordCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFIX_V1 = "ENC:1:";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

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
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("PASSWORD_ENCRYPTION_KEY must decode to "
                    + KEY_LENGTH_BYTES + " bytes for AES-256, got " + keyBytes.length);
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** Encrypts {@code plaintext}, binding the ciphertext to {@code userId} via GCM associated data. */
    public String encrypt(String plaintext, String userId) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LENGTH_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH_BYTES, ciphertext.length);

            return PREFIX_V1 + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to encrypt password", e);
        }
    }

    /** Decrypts a stored {@code ENC:1:} value; any other format is rejected. */
    public String decrypt(String stored, String userId) {
        if (stored == null || !stored.startsWith(PREFIX_V1)) {
            throw new IllegalStateException("Stored password is not in the expected encrypted format");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX_V1.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new RuntimeException("Failed to decrypt password", e);
        }
    }
}
