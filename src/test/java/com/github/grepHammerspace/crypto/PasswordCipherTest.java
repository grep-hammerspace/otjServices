package com.github.grepHammerspace.crypto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testutil.TestKeys;

import java.util.Base64;

/**
 * Unit tests for {@link PasswordCipher}.
 */
public class PasswordCipherTest {

    private static final String USER_ID = "test-user-id";

    private PasswordCipher cipher;

    @BeforeEach
    public void setUp() {
        cipher = new PasswordCipher(TestKeys.PASSWORD_ENCRYPTION_KEY);
    }

    @Test
    public void decrypt_reversesEncrypt() {
        String plaintext = "supersecret123";

        String encrypted = cipher.encrypt(plaintext, USER_ID);
        String decrypted = cipher.decrypt(encrypted, USER_ID);

        Assertions.assertEquals(plaintext, decrypted);
    }

    @Test
    public void encrypt_prefixesWithVersionedEnc() {
        String encrypted = cipher.encrypt("supersecret123", USER_ID);

        Assertions.assertTrue(encrypted.startsWith("ENC:1:"));
    }

    @Test
    public void encrypt_sameInputTwice_producesDifferentCiphertext() {
        String first = cipher.encrypt("supersecret123", USER_ID);
        String second = cipher.encrypt("supersecret123", USER_ID);

        Assertions.assertNotEquals(first, second, "IV should be randomized on every call");
    }

    @Test
    public void decrypt_plaintextValue_isRejected() {
        Assertions.assertThrows(IllegalStateException.class,
                () -> cipher.decrypt("plain-old-password", USER_ID),
                "unencrypted values must never pass through");
    }

    @Test
    public void decrypt_null_isRejected() {
        Assertions.assertThrows(IllegalStateException.class, () -> cipher.decrypt(null, USER_ID));
    }

    @Test
    public void decrypt_withWrongKey_throws() {
        String encrypted = cipher.encrypt("supersecret123", USER_ID);
        PasswordCipher wrongKeyCipher = new PasswordCipher("//////////////////////////////////////////8=");

        Assertions.assertThrows(RuntimeException.class, () -> wrongKeyCipher.decrypt(encrypted, USER_ID));
    }

    @Test
    public void decrypt_withWrongUserId_throws() {
        String encrypted = cipher.encrypt("supersecret123", USER_ID);

        Assertions.assertThrows(RuntimeException.class, () -> cipher.decrypt(encrypted, "other-user"),
                "ciphertext moved to another user's row should fail GCM authentication");
    }

    @Test
    public void decrypt_corruptPayload_throwsWrapped() {
        Assertions.assertThrows(RuntimeException.class, () -> cipher.decrypt("ENC:1:!!not-base64!!", USER_ID));
        Assertions.assertThrows(RuntimeException.class, () -> cipher.decrypt("ENC:1:AAAA", USER_ID));
    }

    @Test
    public void constructor_rejectsWrongKeyLength() {
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);

        Assertions.assertThrows(IllegalStateException.class, () -> new PasswordCipher(sixteenBytes));
    }
}
