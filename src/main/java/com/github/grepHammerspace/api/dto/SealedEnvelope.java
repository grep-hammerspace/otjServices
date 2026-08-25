package com.github.grepHammerspace.api.dto;

/**
 * The body both prepare endpoints now take: the user's OneAdvanced credentials, encrypted to this
 * server's public key by the client before the request was made.
 *
 * <p>Why, when the request already travels over TLS: it does not travel over <em>one</em> TLS
 * connection. {@code otj-services.com} is proxied by Cloudflare, so the visitor's TLS is
 * terminated at the edge and a second connection is opened to the origin. The plaintext body
 * exists, in the clear, inside Cloudflare for the length of that hop — which is exactly where a
 * WAF rule, a log sample or a compromised edge would find a user's real institutional password.
 * Wrapping the two fields the user cannot afford to lose in a layer Cloudflare has no key for is
 * the point of this record.
 *
 * <p>Field by field, all binary values base64url without padding:
 * <ul>
 *   <li>{@code v} — format version. Only {@code 1} exists; a mismatch is refused rather than
 *       guessed at.</li>
 *   <li>{@code keyId} — which server key the client sealed to, from
 *       {@code GET /otj-services/crypto/public-key}. The server keeps the previous key as well as
 *       the current one, so a rotation mid-flight does not fail a submit.</li>
 *   <li>{@code epk} — the client's ephemeral X25519 public key, 32 bytes. Fresh per request: it
 *       is what makes every envelope's key unique, and what gives the scheme forward secrecy
 *       against a later compromise of the client.</li>
 *   <li>{@code nonce} — 12 bytes for ChaCha20-Poly1305.</li>
 *   <li>{@code ciphertext} — the sealed JSON plus its 16-byte Poly1305 tag.</li>
 * </ul>
 *
 * <p>No {@code toString()} override, unlike {@link OneAdvancedCredentials}: every component here
 * is either public or ciphertext, and being able to log an envelope while debugging a decryption
 * failure is worth more than the symmetry.
 */
public record SealedEnvelope(Integer v, String keyId, String epk, String nonce, String ciphertext) {}
