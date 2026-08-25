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

/**
 * The server half of the sealed-credential scheme: holds the identity key, publishes a signed
 * X25519 key to seal to, and opens the envelopes that come back.
 *
 * <h2>The scheme</h2>
 * <pre>
 *   shared = X25519(clientEphemeralPrivate, serverPublic)          — and the mirror image here
 *   key    = HKDF-SHA256(ikm = shared,
 *                        salt = serverPublicRaw || clientEphemeralPublicRaw,
 *                        info = "otj-oa-credentials-v1")
 *   body   = ChaCha20-Poly1305(key, nonce, aad = "otj-oa-credentials-v1|" + keyId)
 *   plaintext = {"username": "...", "password": "...", "iat": &lt;epoch seconds&gt;}
 * </pre>
 *
 * <p>Both public keys go into the salt so a derived key belongs to exactly one pair of keys, and
 * the keyId goes into the AAD so an envelope cannot be replayed against a different server key
 * than the one it names. ChaCha20-Poly1305 rather than AES-GCM because the client is pure
 * JavaScript on a phone: RN has no WebCrypto to reach for, so this runs in software either way,
 * and ChaCha in software is both faster and free of AES's cache-timing footguns.
 *
 * <h2>What this does and does not defend</h2>
 * <p>It defends the OneAdvanced username and password against everything between the phone and
 * this process — Cloudflare's TLS termination above all, but equally Caddy's logs, a proxy dump,
 * or a heap dump of the edge. It does <b>not</b> hide the bearer token, the URL, or the fact that
 * a submit is happening: those are still ordinary TLS. And it is not a replay defence. Anything
 * holding the session token can replay the whole HTTPS request, envelope included, so a nonce
 * cache here would add state and buy nothing. The {@code iat} check bounds how long a captured
 * envelope stays useful; that is all it claims to do.
 *
 * <h2>Rotation</h2>
 * <p>The X25519 key is generated at startup and again every {@link #KEY_LIFETIME}, and the one it
 * replaced is kept and still accepted. Keeping exactly one predecessor is what stops a client
 * that fetched a key seconds before a rotation from failing; it is not a grace timer, so the
 * previous key stays valid until the <em>next</em> rotation retires it. A restart drops both,
 * which is why {@code unknown_key} exists and why the client re-fetches and retries once.
 *
 * <p>The identity key does not rotate here — it is pinned in the app bundle, so rotating it means
 * shipping an app. It is loaded from {@code CREDENTIAL_IDENTITY_SEED} and this class refuses to
 * start without it, in the same spirit as {@code AdminAllowlist} failing closed: a server that
 * quietly invented an identity key would publish announcements no released app can verify, and
 * every user's submit would fail with a signature error that looks like an attack.
 */
@Singleton
public class CredentialKeyRing {
    private static final Logger log = LoggerFactory.getLogger(CredentialKeyRing.class);

    /** The name in {@link CredentialKeyResponse#algorithm()}, and the string the client checks. */
    public static final String ALGORITHM = "X25519-HKDF-SHA256/ChaCha20-Poly1305";
    /** Bound into the derivation, and into the AAD alongside the keyId. */
    static final String INFO = "otj-oa-credentials-v1";
    /** Prefix of the signed announcement string. */
    static final String ANNOUNCEMENT_CONTEXT = "otj-credential-key-v1";
    static final String SEED_ENV = "CREDENTIAL_IDENTITY_SEED";

    private static final int VERSION = 1;
    private static final int NONCE_LEN = 12;
    private static final int TAG_LEN = 16;
    private static final Duration KEY_LIFETIME = Duration.ofHours(24);
    /** How far either side of now a sealed {@code iat} may sit. Phones' clocks are not exact. */
    private static final Duration MAX_SKEW = Duration.ofMinutes(5);

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final PrivateKey identityPrivate;

    /** Guards the two-field rotation only; opening an envelope needs no lock. */
    private final Object rotationLock = new Object();
    private volatile Entry current;
    private volatile Entry previous;

    /** Reads {@link #SEED_ENV}. Bound by {@code AppModule}; the tests bind a fixed seed instead. */
    public CredentialKeyRing() {
        this(seedFromEnvironment());
    }

    /**
     * Takes the 32-byte Ed25519 seed directly. Used by the tests, which need a fixed identity so a
     * scenario can verify a signature, and by {@link IdentityKeyTool}.
     */
    public CredentialKeyRing(byte[] identitySeed) {
        try {
            this.identityPrivate = RawKeys.ed25519Private(identitySeed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not load the credential identity key", e);
        }
        this.current = newEntry();
        // The public half is deliberately absent from this line, and from the announcement: the
        // JDK offers no way to derive an Ed25519 public key from its seed, and the one place it is
        // needed — the app's pinned copy — got it from IdentityKeyTool when the seed was minted.
        log.info("Credential identity key loaded — first keyId={}", current.keyId);
    }

    /**
     * The signed announcement for the current key, rotating first if it has expired.
     *
     * <p>Rotation is lazy rather than scheduled: this endpoint is the only thing that needs a
     * current key, so there is nothing for a timer to be early for, and a lazily rotated ring has
     * no thread to shut down in tests.
     */
    public CredentialKeyResponse announce() {
        Entry entry = current;
        if (Instant.now().isAfter(entry.expiresAt)) {
            synchronized (rotationLock) {
                // Re-read inside the lock: two requests can arrive past the expiry together, and
                // only one of them should rotate. Losing this race would retire a key that the
                // winner had just published.
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

    /**
     * Opens an envelope and returns the JSON that was sealed inside it.
     *
     * <p>Returns bytes rather than a parsed record so this class owns no opinion about what is
     * inside — {@code OtjServicesResource} does the Jackson step, and the {@code iat} check is the
     * one exception, made here because it is part of the seal rather than part of the payload.
     */
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
            // Type only, and no cause: a bad tag and a bad public key must look identical from
            // outside, and the JDK's messages for the two do not.
            log.warn("Rejected a sealed credential envelope — {}", e.getClass().getSimpleName());
            throw new SealedCredentialsException(Reason.UNDECRYPTABLE);
        }
        return plaintext;
    }

    /**
     * Checks the sealed timestamp. Called by the resource once it has parsed {@code iat} out of
     * the plaintext, because the parse and the check belong on opposite sides of the JSON layer.
     */
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
        // The JDK's XDH already refuses an all-zero result (RFC 7748 §6.1), but this costs
        // nothing and means the check is visible where someone reads the derivation.
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
            // Eight bytes of SHA-256 over the key itself. Derived rather than random so the same
            // key always has the same id, and short because it is an identifier, not a digest to
            // be trusted — the signature is what makes the announcement believable.
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

    /** Accepts either base64 alphabet — a seed gets copied through shells and .env files. */
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
