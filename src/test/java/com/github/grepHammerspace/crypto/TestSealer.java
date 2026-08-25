package com.github.grepHammerspace.crypto;

import com.github.grepHammerspace.api.dto.SealedEnvelope;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * The client half of the sealed-credential scheme, in Java, for tests.
 *
 * <p>Deliberately written against the wire format rather than by calling into
 * {@link CredentialKeyRing} — it is a second implementation, and that is the point. A test that
 * sealed by reusing the server's own code would pass just as happily if both sides agreed on the
 * wrong thing; this one only passes if the format is what the spec says it is, which is what the
 * Expo client will be implementing separately in {@code @noble/*}.
 *
 * <p>It is not a substitute for testing the real client. What it pins is the format: the salt
 * order, the AAD string, the nonce length, and that the plaintext is JSON with an {@code iat}.
 */
public final class TestSealer {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private TestSealer() {}

    public static SealedEnvelope seal(String serverPublicKeyB64, String keyId,
                                      String username, String password) throws Exception {
        return seal(serverPublicKeyB64, keyId, username, password,
                java.time.Instant.now().getEpochSecond());
    }

    /** The form with an explicit {@code iat}, for the staleness scenarios. */
    public static SealedEnvelope seal(String serverPublicKeyB64, String keyId, String username,
                                      String password, long issuedAt) throws Exception {
        return sealRaw(serverPublicKeyB64, keyId,
                "{\"username\":\"" + username + "\",\"password\":\"" + password
                        + "\",\"iat\":" + issuedAt + "}");
    }

    /**
     * Seals a payload verbatim.
     *
     * <p>The scenarios that put something unexpected inside the envelope need this: once the
     * credentials are encrypted, "what happens if the client sends a field it should not" is a
     * question about the plaintext, and there is no way to ask it through a typed helper.
     */
    public static SealedEnvelope sealRaw(String serverPublicKeyB64, String keyId, String json)
            throws Exception {
        byte[] serverPublicRaw = B64D.decode(serverPublicKeyB64);

        KeyPair ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] ephemeralPublicRaw = RawKeys.rawPublic(ephemeral.getPublic());

        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(ephemeral.getPrivate());
        agreement.doPhase(RawKeys.x25519Public(serverPublicRaw), true);

        byte[] salt = Arrays.copyOf(serverPublicRaw, serverPublicRaw.length + ephemeralPublicRaw.length);
        System.arraycopy(ephemeralPublicRaw, 0, salt, serverPublicRaw.length, ephemeralPublicRaw.length);
        byte[] key = Hkdf.derive(agreement.generateSecret(), salt, CredentialKeyRing.INFO);

        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        cipher.updateAAD((CredentialKeyRing.INFO + "|" + keyId).getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));

        return new SealedEnvelope(1, keyId, B64.encodeToString(ephemeralPublicRaw),
                B64.encodeToString(nonce), B64.encodeToString(ciphertext));
    }
}
