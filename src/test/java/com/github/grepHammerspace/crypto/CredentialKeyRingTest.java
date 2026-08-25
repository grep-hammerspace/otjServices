package com.github.grepHammerspace.crypto;

import com.github.grepHammerspace.api.dto.CredentialKeyResponse;
import com.github.grepHammerspace.api.dto.SealedEnvelope;
import com.github.grepHammerspace.crypto.SealedCredentialsException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the server half of the scheme has to guarantee, from the outside.
 *
 * <p>The envelopes come from {@link TestSealer}, which builds them from the format description
 * rather than by calling this class — see the note there on why that separation is the whole value
 * of these tests.
 */
class CredentialKeyRingTest {

    /**
     * A real Ed25519 pair, with the seed pulled out of its PKCS#8 encoding the way
     * {@link IdentityKeyTool} does it. Generated rather than hard-coded because that is the only
     * route to a matching public key: the JDK will not derive one from a seed, so a fixed seed
     * would leave {@link #announcementIsSigned()} with nothing to verify against.
     */
    private static final java.security.KeyPair IDENTITY = identityKeyPair();
    private static final byte[] SEED = seedOf(IDENTITY);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    @Test
    @DisplayName("A sealed envelope comes back out as the credentials that went in")
    void roundTrip() throws Exception {
        CredentialKeyRing ring = new CredentialKeyRing(SEED);
        CredentialKeyResponse key = ring.announce();

        SealedEnvelope envelope = TestSealer.seal(key.publicKey(), key.keyId(),
                "learner@example.invalid", "hunter2-but-longer");

        String json = new String(ring.open(envelope), StandardCharsets.UTF_8);
        assertTrue(json.contains("learner@example.invalid"), json);
        assertTrue(json.contains("hunter2-but-longer"), json);
    }

    @Test
    @DisplayName("The announcement is signed by the identity key, over exactly the published fields")
    void announcementIsSigned() throws Exception {
        CredentialKeyResponse key = new CredentialKeyRing(SEED).announce();

        String signed = "otj-credential-key-v1|" + key.keyId() + "|" + key.publicKey()
                + "|" + key.expiresAt();
        Signature verifier = Signature.getInstance("Ed25519");
        // Verified against the public half of the pair the seed came out of — which is exactly
        // what the app pins, and exactly what IdentityKeyTool prints alongside the seed.
        verifier.initVerify(IDENTITY.getPublic());
        verifier.update(signed.getBytes(StandardCharsets.UTF_8));

        assertTrue(verifier.verify(B64D.decode(key.signature())),
                "the app pins this key and will refuse a submit if this signature does not verify");
        assertEquals(CredentialKeyRing.ALGORITHM, key.algorithm());
    }

    @Test
    @DisplayName("A flipped byte of ciphertext is refused, and says nothing about why")
    void tamperedCiphertextIsRefused() throws Exception {
        CredentialKeyRing ring = new CredentialKeyRing(SEED);
        CredentialKeyResponse key = ring.announce();
        SealedEnvelope good = TestSealer.seal(key.publicKey(), key.keyId(), "u", "p");

        byte[] bytes = B64D.decode(good.ciphertext());
        bytes[0] ^= 0x01;
        SealedEnvelope tampered = new SealedEnvelope(good.v(), good.keyId(), good.epk(),
                good.nonce(), B64.encodeToString(bytes));

        assertEquals(Reason.UNDECRYPTABLE,
                assertThrows(SealedCredentialsException.class, () -> ring.open(tampered)).reason());
    }

    @Test
    @DisplayName("An envelope sealed to another server's key is refused as undecryptable")
    void wrongServerKeyIsRefused() throws Exception {
        CredentialKeyRing ring = new CredentialKeyRing(SEED);
        String keyId = ring.announce().keyId();

        // Same keyId, different key underneath — what an edge that substituted its own key would
        // produce if the app did not check the signature.
        String impostor = B64.encodeToString(RawKeys.rawPublic(
                KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic()));
        SealedEnvelope envelope = TestSealer.seal(impostor, keyId, "u", "p");

        assertEquals(Reason.UNDECRYPTABLE,
                assertThrows(SealedCredentialsException.class, () -> ring.open(envelope)).reason());
    }

    @Test
    @DisplayName("A keyId this server never had is unknown_key, so the client knows to re-fetch")
    void unknownKeyId() throws Exception {
        CredentialKeyRing ring = new CredentialKeyRing(SEED);
        CredentialKeyResponse key = ring.announce();
        SealedEnvelope envelope = TestSealer.seal(key.publicKey(), "AAAAAAAAAAA", "u", "p");

        assertEquals(Reason.UNKNOWN_KEY,
                assertThrows(SealedCredentialsException.class, () -> ring.open(envelope)).reason());
    }

    @Test
    @DisplayName("The keyId is bound into the tag, so an envelope cannot be re-labelled")
    void keyIdIsAuthenticated() throws Exception {
        CredentialKeyRing ring = new CredentialKeyRing(SEED);
        CredentialKeyResponse key = ring.announce();
        // Sealed with the right key but claiming a different (also right) id: the AAD no longer
        // matches, so the tag fails. Without the keyId in the AAD this would decrypt.
        SealedEnvelope envelope = TestSealer.seal(key.publicKey(), "not-the-key-id", "u", "p");
        SealedEnvelope relabelled = new SealedEnvelope(1, key.keyId(), envelope.epk(),
                envelope.nonce(), envelope.ciphertext());

        assertEquals(Reason.UNDECRYPTABLE,
                assertThrows(SealedCredentialsException.class, () -> ring.open(relabelled)).reason());
    }

    @Test
    @DisplayName("A version this build does not implement is refused rather than guessed at")
    void unsupportedVersion() throws Exception {
        CredentialKeyRing ring = new CredentialKeyRing(SEED);
        CredentialKeyResponse key = ring.announce();
        SealedEnvelope good = TestSealer.seal(key.publicKey(), key.keyId(), "u", "p");
        SealedEnvelope future = new SealedEnvelope(2, good.keyId(), good.epk(), good.nonce(),
                good.ciphertext());

        assertEquals(Reason.UNSUPPORTED_VERSION,
                assertThrows(SealedCredentialsException.class, () -> ring.open(future)).reason());
    }

    @Test
    @DisplayName("Missing and misshapen fields are malformed, not crashes")
    void malformedEnvelopes() {
        CredentialKeyRing ring = new CredentialKeyRing(SEED);
        String keyId = ring.announce().keyId();

        assertEquals(Reason.MALFORMED_ENVELOPE, reasonOf(ring, null));
        assertEquals(Reason.MALFORMED_ENVELOPE,
                reasonOf(ring, new SealedEnvelope(null, keyId, "AA", "AA", "AA")));
        // A 31-byte ephemeral key: the length check exists so this is a 400 rather than an
        // InvalidKeySpecException surfacing as a 500.
        assertEquals(Reason.MALFORMED_ENVELOPE, reasonOf(ring, new SealedEnvelope(1, keyId,
                B64.encodeToString(new byte[31]), B64.encodeToString(new byte[12]),
                B64.encodeToString(new byte[32]))));
        assertEquals(Reason.MALFORMED_ENVELOPE, reasonOf(ring, new SealedEnvelope(1, keyId,
                "not base64url!!", B64.encodeToString(new byte[12]),
                B64.encodeToString(new byte[32]))));
    }

    @Test
    @DisplayName("Freshness bounds how long a captured envelope stays useful")
    void freshness() throws Exception {
        CredentialKeyRing ring = new CredentialKeyRing(SEED);
        long now = Instant.now().getEpochSecond();

        ring.checkFreshness(now);
        ring.checkFreshness(now - 60);
        // A phone whose clock is a couple of minutes fast must still be able to submit.
        ring.checkFreshness(now + 120);

        assertEquals(Reason.STALE_ENVELOPE, assertThrows(SealedCredentialsException.class,
                () -> ring.checkFreshness(now - 3600)).reason());
        assertEquals(Reason.MALFORMED_ENVELOPE, assertThrows(SealedCredentialsException.class,
                () -> ring.checkFreshness(null)).reason());
    }

    @Test
    @DisplayName("Two rings never publish the same key")
    void keysAreGeneratedPerProcess() {
        assertNotEquals(new CredentialKeyRing(SEED).announce().keyId(),
                new CredentialKeyRing(SEED).announce().keyId(),
                "the X25519 key is per-process; only the identity key is shared");
    }

    private static java.security.KeyPair identityKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] seedOf(java.security.KeyPair pair) {
        byte[] encoded = pair.getPrivate().getEncoded();
        return java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    private static Reason reasonOf(CredentialKeyRing ring, SealedEnvelope envelope) {
        return assertThrows(SealedCredentialsException.class, () -> ring.open(envelope)).reason();
    }
}
