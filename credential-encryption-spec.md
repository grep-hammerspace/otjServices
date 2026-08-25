# Sealed OneAdvanced credentials — wire format

The two prepare endpoints take the user's OneAdvanced username and password encrypted to this
service, rather than as plaintext JSON. This document is the contract between
`crypto/CredentialKeyRing` here and `src/lib/credential-seal.ts` in `otj-mobile`. Both sides
implement it independently — there is no shared library — so this file, not either implementation,
is the thing to change first.

## Why

`otj-services.com` is proxied by Cloudflare. The visitor's TLS terminates at an edge node, which
opens its own connection to the origin. Whatever is in the request body is therefore in plaintext
inside Cloudflare for that hop: available to a WAF rule, a log sample, a support tool, or anyone
who compromises the edge.

For the bearer token that is a nuisance — it is this service's own, scoped to this service, and
revocable. For the OneAdvanced password it is not: that is the user's real institutional
credential, it opens far more than this app, and this service deliberately does not store it
precisely so that there is only ever one copy. Encrypting it end to end is what makes that stop
being a lie in the one place it travels.

**This does not hide anything else.** The bearer token, the URL, the timing and the fact that a
submit is happening are all still ordinary TLS, visible at the edge. The scheme covers exactly two
fields, and only between the phone and this process.

## Trust anchor

The client fetches the key it seals to from `GET /otj-services/crypto/public-key` — over the same
proxied connection. A client that trusted the answer would be protected against an edge that reads
and not at all against one that answers: substituting its own X25519 key would let it decrypt, read
and re-seal transparently.

So the announcement is **signed** with a long-lived Ed25519 identity key, and the app carries the
public half as a build-time constant. An announcement that does not verify makes the app refuse to
submit. There is no plaintext fallback, and there is no "trust on first use" — a fallback is a
downgrade attack with extra steps.

The consequence to accept up front: **rotating the identity key means shipping an app build.** The
X25519 key it signs rotates freely; the signer does not.

## Endpoint

```
GET /otj-services/crypto/public-key        (bearer token required)

200 {
  "algorithm": "X25519-HKDF-SHA256/ChaCha20-Poly1305",
  "keyId":     "<base64url, 8 bytes>",
  "publicKey": "<base64url, 32 bytes — X25519>",
  "expiresAt": 1755440000,
  "signature": "<base64url, 64 bytes — Ed25519>"
}
```

`signature` covers the ASCII string

```
otj-credential-key-v1|{keyId}|{publicKey}|{expiresAt}
```

with each field exactly as it appears in the response. `expiresAt` is epoch **seconds**, not an ISO
timestamp, because the client has to rebuild this string byte for byte and date formatting is the
classic way two implementations quietly disagree.

The identity public key is not in the response. Publishing it next to the signature invites a
client to verify a signature against a key from the same channel, which proves nothing. It is also
not available to publish — the JDK cannot derive an Ed25519 public key from its seed, which is why
`IdentityKeyTool generate` prints both halves at minting time and they are deployed as a pair.

## Envelope

Both `POST /otj-services/prepare-browser` and `POST /otj-services/azure-id/prepare` take:

```json
{
  "v": 1,
  "keyId": "<from the announcement>",
  "epk": "<base64url, 32 bytes — the client's ephemeral X25519 public key>",
  "nonce": "<base64url, 12 bytes>",
  "ciphertext": "<base64url — sealed JSON + 16-byte Poly1305 tag>"
}
```

All binary fields are base64url **without padding**.

```
shared = X25519(ephemeralPrivate, serverPublic)
key    = HKDF-SHA256(ikm  = shared,
                     salt = serverPublicRaw || ephemeralPublicRaw,
                     info = "otj-oa-credentials-v1",
                     L    = 32)
ciphertext = ChaCha20-Poly1305(key, nonce,
                               aad = "otj-oa-credentials-v1|" + keyId,
                               plaintext)

plaintext = {"username": "...", "password": "...", "iat": <epoch seconds>}
```

Decisions worth not re-litigating:

- **Both public keys are in the salt**, in that order, so a derived key belongs to exactly one pair
  of keys.
- **The keyId is in the AAD**, so an envelope cannot be re-labelled as sealed to a different key.
- **`iat` is inside the ciphertext.** Beside it, anything on the path could edit it, and the
  freshness check would mean nothing. The server allows ±5 minutes — phones' clocks are not exact.
- **ChaCha20-Poly1305, not AES-GCM.** The client is pure JavaScript on a phone: React Native has no
  WebCrypto and this project cannot use a native crypto module (SDK 54 is pinned so the app runs in
  Expo Go), so this runs in software either way. ChaCha in software is faster and has none of AES's
  cache-timing footguns.
- **This is not a replay defence.** Anything holding the bearer token can replay the entire HTTPS
  request, envelope included. A server-side nonce cache would add state and buy nothing. `iat`
  bounds how long a captured envelope stays useful; that is all it claims.

## Errors

All are `400` with `{"error": "<for a person>", "code": "<for the client>"}`.

| code | meaning | what the client does |
|---|---|---|
| `unknown_key` | sealed to a key this process no longer holds — usually a restart | re-fetch the key and seal again, **once** |
| `undecryptable` | the tag did not verify: wrong key, tampering, corruption | show the message |
| `malformed_envelope` | missing field, wrong length, not base64url, or the plaintext was not the expected JSON | show the message |
| `unsupported_version` | a `v` this build does not implement | show the message |
| `stale_envelope` | `iat` more than 5 minutes from now | show the message |

A wrong key and a flipped bit are deliberately the same answer. Telling them apart is a decryption
oracle, and the plaintext never reaches the driver in either case.

## Key lifecycle

- The **X25519 key** is generated at startup and again every 24 hours. The key it replaces is kept
  and still accepted until the *next* rotation, so a client that fetched seconds before a rotation
  does not fail. A restart drops both — hence `unknown_key` and the client's single retry.
- The **identity key** comes from `CREDENTIAL_IDENTITY_SEED` (base64, 32 bytes). The service
  refuses to start without it. A generated one would publish announcements no released app can
  verify, and every user's submit would fail with what looks like an attack.

```bash
# mint the pair — prints the server's seed and the app's pinned public key
java -cp target/app.jar com.github.grepHammerspace.crypto.IdentityKeyTool generate

# check which identity a running box is using
curl -s https://otj-services.com/otj-services/crypto/public-key -H "Authorization: Bearer $TOKEN" \
  | java -cp target/app.jar com.github.grepHammerspace.crypto.IdentityKeyTool verify <publicKey>
```

## Deploying this change

The cutover is hard: the prepare endpoints stop accepting plaintext bodies in the same commit that
starts accepting envelopes. An older app build gets a 400. That is deliberate — a server that still
took the old shape would leave the plaintext path open to exactly the party being defended against,
and no client-side flag can close a door the server holds open.

So the order is:

1. Mint the identity pair.
2. Put `CREDENTIAL_IDENTITY_SEED` in `~/otj-hours-api.env` on the box (`chmod 600`).
3. Put the printed public key in `otj-mobile/.env` as `EXPO_PUBLIC_CREDENTIAL_IDENTITY_KEY`.
4. Deploy the backend and reload the app together. Between the two, submitting does not work.

Steps 2 and 3 are one action in two places: a backend holding a seed the app does not pin, or an
app pinning a key the backend does not hold, fails every submit with a signature error.
