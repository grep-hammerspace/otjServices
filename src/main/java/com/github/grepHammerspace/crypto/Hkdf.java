package com.github.grepHammerspace.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

// HKDF-SHA256 (RFC 5869), limited to one 32-byte output block: T(1) is all the format needs.
public final class Hkdf {
    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    public static byte[] derive(byte[] ikm, byte[] salt, String info) throws GeneralSecurityException {
        return derive(ikm, salt, info.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info) throws GeneralSecurityException {
        return expand(extract(salt, ikm), info);
    }

    private static byte[] extract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[HASH_LEN] : salt, HMAC));
        return mac.doFinal(ikm);
    }

    private static byte[] expand(byte[] prk, byte[] info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(prk, HMAC));
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.writeBytes(info);
        block.write(1);
        return mac.doFinal(block.toByteArray());
    }
}
