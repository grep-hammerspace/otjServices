package com.github.grepHammerspace.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The raw ↔ encoded conversions, both curves, both directions.
 *
 * <p>Worth its own class because the failure mode is quiet. {@code rawPublic} used to choose its
 * prefix from {@link java.security.Key#getAlgorithm()}, which looks obviously right and is wrong:
 * the JDK answers {@code "EdDSA"} for an Ed25519 key and {@code "XDH"} for an X25519 one, so the
 * Ed25519 branch was unreachable. Nothing caught it — every test in
 * {@link CredentialKeyRingTest} happened to pass X25519 keys — until
 * {@code IdentityKeyTool generate} threw on the one line that mints a deployment's identity.
 */
class RawKeysTest {

    @Test
    @DisplayName("An X25519 public key survives the round trip")
    void x25519RoundTrip() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] raw = RawKeys.rawPublic(pair.getPublic());

        assertEquals(RawKeys.KEY_LEN, raw.length);
        assertArrayEquals(pair.getPublic().getEncoded(),
                RawKeys.x25519Public(raw).getEncoded());
    }

    @Test
    @DisplayName("An Ed25519 public key survives the round trip — the case getAlgorithm() misnames")
    void ed25519RoundTrip() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] raw = RawKeys.rawPublic(pair.getPublic());

        assertEquals(RawKeys.KEY_LEN, raw.length);
        assertArrayEquals(pair.getPublic().getEncoded(), RawKeys.ed25519Public(raw).getEncoded());
    }

    @Test
    @DisplayName("A seed taken out of a PKCS#8 encoding rebuilds the same signing key")
    void ed25519SeedRoundTrip() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = pair.getPrivate().getEncoded();
        byte[] seed = Arrays.copyOfRange(encoded, encoded.length - RawKeys.KEY_LEN, encoded.length);

        // This is the whole contract between IdentityKeyTool and CREDENTIAL_IDENTITY_SEED: the
        // seed printed at minting time has to produce a key whose signatures the printed public
        // key verifies, across a restart and a different process.
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(RawKeys.ed25519Private(seed));
        signer.update("announcement".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(RawKeys.ed25519Public(RawKeys.rawPublic(pair.getPublic())));
        verifier.update("announcement".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(verifier.verify(signature));
    }

    @Test
    @DisplayName("A key of the wrong length is refused rather than wrapped into nonsense")
    void wrongLength() {
        assertThrows(IllegalArgumentException.class, () -> RawKeys.x25519Public(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> RawKeys.ed25519Private(new byte[33]));
        assertThrows(IllegalArgumentException.class, () -> RawKeys.x25519Private(null));
    }
}
