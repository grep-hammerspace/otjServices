package com.github.grepHammerspace.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

// Raw 32-byte X25519/Ed25519 keys <-> the JDK's encodings. For these curves the SPKI and PKCS#8
// wrappers are fixed prefixes, so wrapping is concatenation.
public final class RawKeys {
    private static final byte[] X25519_SPKI = HexFormat.of().parseHex("302a300506032b656e032100");
    private static final byte[] X25519_PKCS8 = HexFormat.of().parseHex("302e020100300506032b656e04220420");
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

    // The curve comes from the encoding's OID: getAlgorithm() says "EdDSA"/"XDH", not the name asked for.
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
