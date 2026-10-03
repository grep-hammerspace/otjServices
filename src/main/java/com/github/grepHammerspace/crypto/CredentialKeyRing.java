package com.github.grepHammerspace.crypto;

import com.github.grepHammerspace.api.dto.CredentialKeyResponse;
import com.github.grepHammerspace.api.dto.SealedEnvelope;
import com.github.grepHammerspace.crypto.SealedCredentialsException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

// Wire format: credential-encryption-spec.md. The X25519 key rotates every KEY_LIFETIME and one
// predecessor is still accepted, so a client that fetched just before a rotation doesn't fail.
// The identity key never rotates here: the app pins its public half.
@Singleton
public class CredentialKeyRing {
    private static final Logger log = LoggerFactory.getLogger(CredentialKeyRing.class);

    public static final String ALGORITHM = "X25519-HKDF-SHA256/ChaCha20-Poly1305";
    static final String INFO = "otj-oa-credentials-v1";
    static final String ANNOUNCEMENT_CONTEXT = "otj-credential-key-v1";
    static final String SEED_ENV = "CREDENTIAL_IDENTITY_SEED";

    private static final int VERSION = 1;
    private static final int NONCE_LEN = 12;
    private static final int TAG_LEN = 16;
    private static final Duration KEY_LIFETIME = Duration.ofHours(24);
    private static final Duration MAX_SKEW = Duration.ofMinutes(5);

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final PrivateKey identityPrivate;

    private final Object rotationLock = new Object();
    private volatile Entry current;
    private volatile Entry previous;

    public CredentialKeyRing() {
        this(seedFromEnvironment());
    }

    public CredentialKeyRing(byte[] identitySeed) {
        try {
            this.identityPrivate = RawKeys.ed25519Private(identitySeed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not load the credential identity key", e);
        }
        this.current = newEntry();
        log.info("Credential identity key loaded — first keyId={}", current.keyId);
    }

    public CredentialKeyResponse announce() {
        Entry entry = current;
        if (Instant.now().isAfter(entry.expiresAt)) {
            synchronized (rotationLock) {
                // Re-read: two requests past the expiry must not both rotate, or the second
                // retires the key the first just published.
                if (Instant.now().isAfter(current.expiresAt)) {
                    previous = current;
                    current = newEntry();
                    log.info("Rotated the credential key — new keyId={}, previous kept as {}",
                            current.keyId, previous.keyId);
                }
                entry = current;
            }
        }
        return entry.announcement;
    }

    public byte[] open(SealedEnvelope envelope) throws SealedCredentialsException {
        if (envelope == null || envelope.v() == null) {
            throw new SealedCredentialsException(Reason.MALFORMED_ENVELOPE);
        }
        if (envelope.v() != VERSION) {
            throw new SealedCredentialsException(Reason.UNSUPPORTED_VERSION);
        }

        byte[] epk = decode(envelope.epk(), RawKeys.KEY_LEN);
        byte[] nonce = decode(envelope.nonce(), NONCE_LEN);
        byte[] ciphertext = decode(envelope.ciphertext(), -1);
        if (ciphertext.length <= TAG_LEN) {
            throw new SealedCredentialsException(Reason.MALFORMED_ENVELOPE);
        }

        Entry entry = entryFor(envelope.keyId());

        byte[] plaintext;
        try {
            byte[] shared = agree(entry.keyPair.getPrivate(), epk);
            byte[] key = Hkdf.derive(shared, salt(entry.publicRaw, epk), INFO);
            Arrays.fill(shared, (byte) 0);

            Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                    new IvParameterSpec(nonce));
            cipher.updateAAD((INFO + "|" + entry.keyId).getBytes(StandardCharsets.UTF_8));
            plaintext = cipher.doFinal(ciphertext);
            Arrays.fill(key, (byte) 0);
        } catch (GeneralSecurityException e) {
            // One reason for every failure: telling them apart is a decryption oracle.
            log.warn("Rejected a sealed credential envelope — {}", e.getClass().getSimpleName());
            throw new SealedCredentialsException(Reason.UNDECRYPTABLE);
        }
        return plaintext;
    }

    public void checkFreshness(Long issuedAtEpochSeconds) throws SealedCredentialsException {
        if (issuedAtEpochSeconds == null) {
            throw new SealedCredentialsException(Reason.MALFORMED_ENVELOPE);
        }
        Duration age = Duration.between(Instant.ofEpochSecond(issuedAtEpochSeconds), Instant.now());
        if (age.abs().compareTo(MAX_SKEW) > 0) {
            throw new SealedCredentialsException(Reason.STALE_ENVELOPE);
        }
    }

    private Entry entryFor(String keyId) throws SealedCredentialsException {
        Entry entry = current;
        if (entry.keyId.equals(keyId)) return entry;
        Entry old = previous;
        if (old != null && old.keyId.equals(keyId)) return old;
        throw new SealedCredentialsException(Reason.UNKNOWN_KEY);
    }

    private static byte[] agree(PrivateKey serverPrivate, byte[] clientPublicRaw)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(serverPrivate);
        agreement.doPhase(RawKeys.x25519Public(clientPublicRaw), true);
        byte[] shared = agreement.generateSecret();
        // The JDK already refuses an all-zero result (RFC 7748 §6.1); this keeps the check visible.
        if (shared.length != RawKeys.KEY_LEN || isAllZero(shared)) {
            throw new GeneralSecurityException("degenerate shared secret");
        }
        return shared;
    }

    private static byte[] salt(byte[] serverPublicRaw, byte[] clientPublicRaw) {
        byte[] salt = Arrays.copyOf(serverPublicRaw, serverPublicRaw.length + clientPublicRaw.length);
        System.arraycopy(clientPublicRaw, 0, salt, serverPublicRaw.length, clientPublicRaw.length);
        return salt;
    }

    private static boolean isAllZero(byte[] bytes) {
        int accumulator = 0;
        for (byte b : bytes) accumulator |= b;
        return accumulator == 0;
    }

    private static byte[] decode(String value, int expectedLength)
            throws SealedCredentialsException {
        if (value == null || value.isBlank()) {
            throw new SealedCredentialsException(Reason.MALFORMED_ENVELOPE);
        }
        byte[] decoded;
        try {
            decoded = B64D.decode(value);
        } catch (IllegalArgumentException e) {
            throw new SealedCredentialsException(Reason.MALFORMED_ENVELOPE);
        }
        if (expectedLength >= 0 && decoded.length != expectedLength) {
            throw new SealedCredentialsException(Reason.MALFORMED_ENVELOPE);
        }
        return decoded;
    }

    private Entry newEntry() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            byte[] publicRaw = RawKeys.rawPublic(pair.getPublic());
            String publicB64 = B64.encodeToString(publicRaw);
            String keyId = B64.encodeToString(Arrays.copyOf(
                    MessageDigest.getInstance("SHA-256").digest(publicRaw), 8));
            long expiresAt = Instant.now().plus(KEY_LIFETIME).getEpochSecond();

            String signed = ANNOUNCEMENT_CONTEXT + "|" + keyId + "|" + publicB64 + "|" + expiresAt;
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(identityPrivate);
            signature.update(signed.getBytes(StandardCharsets.UTF_8));

            return new Entry(keyId, pair, publicRaw, Instant.ofEpochSecond(expiresAt),
                    new CredentialKeyResponse(ALGORITHM, keyId, publicB64, expiresAt,
                            B64.encodeToString(signature.sign())));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not generate a credential key", e);
        }
    }

    private static byte[] seedFromEnvironment() {
        String seed = System.getenv(SEED_ENV);
        if (seed == null || seed.isBlank()) {
            throw new IllegalStateException(SEED_ENV + " is not set. The app pins this key's "
                    + "public half, so a generated one would fail every submit. Generate a seed "
                    + "with:  java -cp app.jar com.github.grepHammerspace.crypto.IdentityKeyTool");
        }
        byte[] decoded = decodeSeed(seed.strip());
        if (decoded.length != RawKeys.KEY_LEN) {
            throw new IllegalStateException(SEED_ENV + " must decode to " + RawKeys.KEY_LEN
                    + " bytes, got " + decoded.length);
        }
        return decoded;
    }

    private static byte[] decodeSeed(String seed) {
        try {
            return Base64.getDecoder().decode(seed);
        } catch (IllegalArgumentException e) {
            return Base64.getUrlDecoder().decode(seed);
        }
    }

    private record Entry(String keyId, KeyPair keyPair, byte[] publicRaw, Instant expiresAt,
                         CredentialKeyResponse announcement) {}
}
