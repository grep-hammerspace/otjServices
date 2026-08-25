package com.github.grepHammerspace.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Converts between the 32-byte raw form of an X25519 / Ed25519 key and the encoded form the JDK's
 * {@link KeyFactory} speaks.
 *
 * <p>The wire format carries raw 32-byte keys, because that is what every other implementation of
 * these curves uses — the client side of this is {@code @noble/curves} in the Expo app, which has
 * no notion of DER at all. The JDK, on the other hand, will only build an {@code XECPublicKey}
 * from either an X.509 {@code SubjectPublicKeyInfo} or an {@code XECPublicKeySpec} holding a
 * {@link java.math.BigInteger} in little-endian u-coordinate form with the high bit masked.
 *
 * <p>This class takes the first route. For these two curves the SPKI and PKCS#8 wrappers are
 * fixed-length constants — there are no optional fields and no length that varies — so wrapping is
 * a byte-array concatenation and unwrapping is a suffix. That is far easier to be sure of than the
 * BigInteger endianness dance, which has a sign-bit trap in it.
 *
 * <p>The prefixes are checked on the way out rather than assumed: if a future JDK ever encodes
 * these differently, this fails loudly here instead of silently publishing 32 bytes that are not
 * the public key.
 */
public final class RawKeys {
    /** {@code SEQUENCE { SEQUENCE { OID 1.3.101.110 }, BIT STRING (0 unused) }} — X25519 SPKI. */
    private static final byte[] X25519_SPKI = HexFormat.of().parseHex("302a300506032b656e032100");
    /** {@code SEQUENCE { INTEGER 0, SEQUENCE { OID 1.3.101.110 }, OCTET STRING { OCTET STRING } }}. */
    private static final byte[] X25519_PKCS8 = HexFormat.of().parseHex("302e020100300506032b656e04220420");
    /** The same two shapes for Ed25519 (OID 1.3.101.112). */
    private static final byte[] ED25519_SPKI = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final byte[] ED25519_PKCS8 = HexFormat.of().parseHex("302e020100300506032b657004220420");

    public static final int KEY_LEN = 32;

    private RawKeys() {}

    public static PublicKey x25519Public(byte[] raw) throws GeneralSecurityException {
        return publicKey("X25519", X25519_SPKI, raw);
    }

    public static PrivateKey x25519Private(byte[] seed) throws GeneralSecurityException {
        return privateKey("X25519", X25519_PKCS8, seed);
    }

    public static PublicKey ed25519Public(byte[] raw) throws GeneralSecurityException {
        return publicKey("Ed25519", ED25519_SPKI, raw);
    }

    public static PrivateKey ed25519Private(byte[] seed) throws GeneralSecurityException {
        return privateKey("Ed25519", ED25519_PKCS8, seed);
    }

    /**
     * The 32 raw bytes behind a JDK public key, taken from its X.509 encoding.
     *
     * <p>Which curve it is comes from the encoding, not from {@link PublicKey#getAlgorithm()}. The
     * name is not the reliable half: a key from {@code KeyPairGenerator.getInstance("Ed25519")}
     * reports its algorithm as {@code "EdDSA"}, and an X25519 one reports {@code "XDH"} — neither
     * matches the name it was asked for. The OID inside the SPKI prefix does distinguish them, and
     * cannot drift.
     */
    public static byte[] rawPublic(PublicKey key) {
        byte[] encoded = key.getEncoded();
        for (byte[] prefix : new byte[][] {X25519_SPKI, ED25519_SPKI}) {
            if (encoded.length == prefix.length + KEY_LEN
                    && Arrays.equals(encoded, 0, prefix.length, prefix, 0, prefix.length)) {
                return Arrays.copyOfRange(encoded, prefix.length, encoded.length);
            }
        }
        throw new IllegalStateException(
                "unexpected " + key.getAlgorithm() + " public key encoding from this JDK");
    }

    private static PublicKey publicKey(String algorithm, byte[] prefix, byte[] raw)
            throws GeneralSecurityException {
        return KeyFactory.getInstance(algorithm)
                .generatePublic(new X509EncodedKeySpec(wrap(prefix, raw, algorithm)));
    }

    private static PrivateKey privateKey(String algorithm, byte[] prefix, byte[] seed)
            throws GeneralSecurityException {
        return KeyFactory.getInstance(algorithm)
                .generatePrivate(new PKCS8EncodedKeySpec(wrap(prefix, seed, algorithm)));
    }

    private static byte[] wrap(byte[] prefix, byte[] raw, String algorithm) {
        if (raw == null || raw.length != KEY_LEN) {
            throw new IllegalArgumentException(
                    "a raw " + algorithm + " key is " + KEY_LEN + " bytes");
        }
        byte[] encoded = Arrays.copyOf(prefix, prefix.length + KEY_LEN);
        System.arraycopy(raw, 0, encoded, prefix.length, KEY_LEN);
        return encoded;
    }
}
