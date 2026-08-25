package com.github.grepHammerspace.api.dto;

// signature is Ed25519 over "otj-credential-key-v1|{keyId}|{publicKey}|{expiresAt}". The identity
// public key is deliberately absent: the app must verify against its pinned copy, not this channel.
public record CredentialKeyResponse(String algorithm, String keyId, String publicKey,
                                    long expiresAt, String signature) {}
