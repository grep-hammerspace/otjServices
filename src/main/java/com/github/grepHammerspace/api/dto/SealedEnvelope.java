package com.github.grepHammerspace.api.dto;

// Binary fields are base64url without padding; see credential-encryption-spec.md.
public record SealedEnvelope(Integer v, String keyId, String epk, String nonce, String ciphertext) {}
