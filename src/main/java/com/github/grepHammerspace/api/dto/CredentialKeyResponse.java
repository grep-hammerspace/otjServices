package com.github.grepHammerspace.api.dto;

/**
 * What {@code GET /otj-services/crypto/public-key} answers: the key to seal credentials to, and a
 * signature proving the answer came from this service rather than from something in front of it.
 *
 * <p>The signature is the part that matters. This response crosses the same Cloudflare hop the
 * credentials do, so a scheme where the client simply trusts the key it is handed defends against
 * a passive edge and nothing else — an active one would hand the client its own key, read the
 * credentials, and re-seal them to the real server with the client none the wiser. Signing the
 * announcement with a long-lived Ed25519 identity key whose public half is <em>pinned in the app
 * bundle</em> is what closes that: an edge that substitutes a key cannot produce a signature the
 * app will accept, and the app refuses to send anything rather than falling back.
 *
 * <p>{@code signature} covers the ASCII string
 * <pre>otj-credential-key-v1|{keyId}|{publicKey}|{expiresAt}</pre>
 * with the fields exactly as they appear here. Epoch seconds rather than an ISO timestamp for
 * {@code expiresAt} on purpose — the client has to rebuild this string byte for byte to verify it,
 * and date formatting is the classic way two implementations disagree about "the same" value.
 *
 * <p>The identity public key is deliberately <b>not</b> in this response. A client that took it
 * from here would be trusting the very channel it is trying to verify, so the only copy that
 * matters is the one compiled into the app; publishing a second copy next to the signature would
 * invite exactly the wrong implementation. It is also not available to publish — the JDK cannot
 * derive an Ed25519 public key from its seed, and {@code IdentityKeyTool} is what printed it when
 * the seed was minted. To check which identity a box is running, verify a live response against
 * the pinned key: {@code IdentityKeyTool verify <publicKey>} reads one from stdin and does it.
 */
public record CredentialKeyResponse(String algorithm, String keyId, String publicKey,
                                    long expiresAt, String signature) {}
