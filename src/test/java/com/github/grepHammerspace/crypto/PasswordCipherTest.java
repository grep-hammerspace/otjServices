package com.github.grepHammerspace.crypto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PasswordCipher}.
 */
public class PasswordCipherTest {

    // 32 zero bytes, base64-encoded — same fixed test key used by TestAppModule/UserRepositoryIT.
    private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private PasswordCipher cipher;

    @BeforeEach
    public void setUp() {
        cipher = new PasswordCipher(TEST_KEY);
    }

    @Test
    public void decrypt_reversesEncrypt() {
        String plaintext = "supersecret123";

        String encrypted = cipher.encrypt(plaintext);
        String decrypted = cipher.decrypt(encrypted);

        Assertions.assertEquals(plaintext, decrypted);
    }

    @Test
    public void encrypt_prefixesWithEnc() {
        String encrypted = cipher.encrypt("supersecret123");

        Assertions.assertTrue(encrypted.startsWith("ENC:"));
    }

    @Test
    public void encrypt_sameInputTwice_producesDifferentCiphertext() {
        String first = cipher.encrypt("supersecret123");
        String second = cipher.encrypt("supersecret123");

        Assertions.assertNotEquals(first, second, "IV should be randomized on every call");
    }

    @Test
    public void decrypt_legacyPlaintext_passesThroughUnchanged() {
        String legacy = "plain-old-password";

        Assertions.assertEquals(legacy, cipher.decrypt(legacy));
    }

    @Test
    public void decrypt_withWrongKey_throws() {
        String encrypted = cipher.encrypt("supersecret123");
        PasswordCipher wrongKeyCipher = new PasswordCipher("//////////////////////////////////////////8=");

        Assertions.assertThrows(RuntimeException.class, () -> wrongKeyCipher.decrypt(encrypted));
    }
}
