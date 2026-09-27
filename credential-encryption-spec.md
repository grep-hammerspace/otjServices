# Sealed OneAdvanced credentials: wire format

The two prepare endpoints take the user's OneAdvanced username and password encrypted to this
process, not as plaintext JSON. This is the contract between `crypto/CredentialKeyRing` here and
`src/lib/credential-seal.ts` in `otj-mobile`. The two are independent implementations, so change
this file first.

## Why

Cloudflare terminates the visitor's TLS, so a request body is plaintext inside Cloudflare. The
OneAdvanced password is the user's institutional credential (on the Azure path, their university
Microsoft account), so it is sealed to this process. Nothing else is: the bearer token, URL, timing
and MFA code are ordinary TLS, visible at the edge.

## Trust anchor

The key to seal to is fetched over the same Cloudflare hop, so an edge that *answered* rather than
read could hand out its own key. The announcement is therefore signed by a long-lived Ed25519
identity key whose public half is built into the app. An announcement that doesn't verify stops
the submit. There is no plaintext fallback and no trust-on-first-use.

Rotating the identity key means shipping a new app build. The X25519 key it signs rotates freely.

## Endpoint

```
GET /otj-services/crypto/public-key        (bearer token required)

200 {
  "algorithm": "X25519-HKDF-SHA256/ChaCha20-Poly1305",
  "keyId":     "<base64url, 8 bytes>",
  "publicKey": "<base64url, 32 bytes, X25519>",
  "expiresAt": 1755440000,
  "signature": "<base64url, 64 bytes, Ed25519>"
}
```

`signature` covers the ASCII string `otj-credential-key-v1|{keyId}|{publicKey}|{expiresAt}`, each
field exactly as it appears in the response. `expiresAt` is epoch seconds so that both sides
rebuild the same bytes.

The identity public key is not in the response: verifying against a key from the same channel
proves nothing.

## Envelope

`POST /otj-services/prepare-browser` and `POST /otj-services/azure-id/prepare` take:

```json
{
  "v": 1,
  "keyId": "<from the announcement>",
  "epk": "<base64url, 32 bytes, the client's ephemeral X25519 public key>",
  "nonce": "<base64url, 12 bytes>",
  "ciphertext": "<base64url, sealed JSON + 16-byte Poly1305 tag>"
}
```

All binary fields are base64url without padding.

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

- **Both public keys are in the salt**, in that order, so a derived key belongs to one pair of keys.
- **The keyId is in the AAD**, so an envelope can't be relabelled as sealed to another key.
- **`iat` is inside the ciphertext**, so nothing on the path can edit it. The server allows ±5 min.
- **ChaCha20-Poly1305, not AES-GCM.** The client is pure JS (Expo Go allows no native crypto
  module), and ChaCha in software is faster and free of AES's cache-timing problems.
- **Not a replay defence.** Whoever holds the bearer token can replay the whole request; `iat` only
  bounds how long a captured envelope is useful.

## Errors

All are `400` with `{"error": "<for a person>", "code": "<for the client>"}`.

| code | meaning | client |
|---|---|---|
| `unknown_key` | sealed to a key this process no longer holds, usually after a restart | re-fetch and re-seal, **once** |
| `undecryptable` | the tag did not verify: wrong key, tampering, corruption | show the message |
| `malformed_envelope` | missing field, wrong length, not base64url, or unexpected plaintext | show the message |
| `unsupported_version` | a `v` this build doesn't implement | show the message |
| `stale_envelope` | `iat` more than 5 minutes from now | show the message |

A wrong key and a flipped bit get the same answer; telling them apart is a decryption oracle.

## Keys

- The **X25519 key** is generated at startup and every 24 hours. Its predecessor is accepted until
  the next rotation. A restart drops both, hence `unknown_key`.
- The **identity key** is `CREDENTIAL_IDENTITY_SEED` (base64 or base64url, 32 bytes). The api role
  won't start without it.

```bash
# mint the pair: the server's seed and the app's pinned public key
java -cp target/app.jar com.github.grepHammerspace.crypto.IdentityKeyTool generate

# check which identity a running box is using
curl -s https://otj-services.com/otj-services/crypto/public-key -H "Authorization: Bearer $TOKEN" \
  | java -cp target/app.jar com.github.grepHammerspace.crypto.IdentityKeyTool verify <publicKey>
```

The seed goes in Parameter Store as `/otj/prod/credential-identity-seed` (SecureString), and the
public key in `otj-mobile/.env` as `EXPO_PUBLIC_CREDENTIAL_IDENTITY_KEY`. They're a pair: a mismatch
fails every submit with a signature error.
