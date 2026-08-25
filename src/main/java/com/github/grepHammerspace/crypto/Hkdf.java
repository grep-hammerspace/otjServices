package com.github.grepHammerspace.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * HKDF-SHA256 (RFC 5869), extract-then-expand.
 *
 * <p>Hand-rolled rather than taken from a library, and deliberately not {@code javax.crypto.KDF}:
 * that API only arrived in JDK 24 and pinning the credential wire format to a very new JDK
 * surface buys nothing here. HKDF over HMAC is thirty lines, has published test vectors
 * ({@link com.github.grepHammerspace.crypto.HkdfTest} runs three of them), and the alternative
 * was adding a crypto dependency to a project that has kept its dependency list short on purpose.
 *
 * <p>Only the 32-byte SHA-256 output length is supported: {@code expand} computes T(1) and stops.
 * That is all the sealed-credential format needs, and the multi-block loop is the part of HKDF
 * that is easy to get subtly wrong. The published vectors ask for longer outputs, so
 * {@code HkdfTest} checks this against the first 32 bytes of three of them — the same computation,
 * since T(1) does not depend on how many blocks follow it.
 */
public final class Hkdf {
    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    /**
     * Derives one 32-byte key from {@code ikm}.
     *
     * @param ikm  the input keying material — here, the raw X25519 shared secret
     * @param salt bound into the extract step; the sealed-credential format passes both public
     *             keys, so a derived key can only belong to one (server key, client key) pair
     * @param info bound into the expand step; the format's version string, so a future format
     *             using the same keys derives a different key
     */
    public static byte[] derive(byte[] ikm, byte[] salt, String info) throws GeneralSecurityException {
        return derive(ikm, salt, info.getBytes(StandardCharsets.UTF_8));
    }

    /** The byte-array form. The vectors use it; the format itself always passes ASCII. */
    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info) throws GeneralSecurityException {
        return expand(extract(salt, ikm), info);
    }

    private static byte[] extract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        // A salt of all zeros is RFC 5869's own default for "no salt", not a shortcut: HMAC
        // treats a zero-length key as a zero-filled block anyway.
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[HASH_LEN] : salt, HMAC));
        return mac.doFinal(ikm);
    }

    private static byte[] expand(byte[] prk, byte[] info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(prk, HMAC));
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.writeBytes(info);
        block.write(1); // T(1) — the counter byte. One block is 32 bytes, which is the whole output.
        return mac.doFinal(block.toByteArray());
    }
}
