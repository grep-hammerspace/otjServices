package com.github.grepHammerspace.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

/**
 * Mints the credential identity key, and checks a live announcement against it.
 *
 * <p>Two subcommands, both meant to be run by hand:
 *
 * <pre>
 *   java -cp app.jar com.github.grepHammerspace.crypto.IdentityKeyTool generate
 *       Prints the seed for the server's CREDENTIAL_IDENTITY_SEED and the public key for the
 *       app's EXPO_PUBLIC_CREDENTIAL_IDENTITY_KEY. Run once per deployment; the public half has
 *       to be built into the app, so replacing it means shipping a new app build.
 *
 *   curl -s https://otj-services.com/otj-services/crypto/public-key -H "Authorization: Bearer $T" \
 *     | java -cp app.jar com.github.grepHammerspace.crypto.IdentityKeyTool verify &lt;publicKey&gt;
 *       Reads an announcement on stdin and says whether the pinned key signed it. This is the
 *       check to run after a deploy, and the one to run when the app starts refusing to submit:
 *       it separates "the box has the wrong seed" from "something is rewriting the response".
 * </pre>
 *
 * <p>It lives in the shipped jar rather than in a script because it has to agree byte for byte
 * with {@link CredentialKeyRing} about what gets signed. A shell reimplementation would be a
 * second definition of that string, free to drift.
 */
public final class IdentityKeyTool {

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "";
        switch (command) {
            case "generate" -> generate();
            case "verify" -> {
                if (args.length < 2) {
                    System.err.println("usage: IdentityKeyTool verify <publicKeyBase64Url>");
                    System.exit(2);
                }
                verify(args[1], System.in);
            }
            default -> {
                System.err.println("usage: IdentityKeyTool generate | verify <publicKeyBase64Url>");
                System.exit(2);
            }
        }
    }

    private static void generate() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        // The seed is the last 32 bytes of the PKCS#8 encoding; RawKeys is the other half of that
        // fact and reverses it when the server loads the value printed here.
        byte[] encoded = pair.getPrivate().getEncoded();
        byte[] seed = java.util.Arrays.copyOfRange(encoded, encoded.length - RawKeys.KEY_LEN, encoded.length);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();

        System.out.println("# Server — put this in the api container's environment. It is a secret.");
        System.out.println(CredentialKeyRing.SEED_ENV + "=" + b64.encodeToString(seed));
        System.out.println();
        System.out.println("# Mobile app — put this in otj-mobile/.env. It is not a secret, but");
        System.out.println("# changing it requires a new app build, so keep the pair together.");
        System.out.println("EXPO_PUBLIC_CREDENTIAL_IDENTITY_KEY="
                + b64.encodeToString(RawKeys.rawPublic(pair.getPublic())));
    }

    private static void verify(String publicKeyB64, InputStream in) throws Exception {
        JsonNode body = new ObjectMapper().readTree(in.readAllBytes());
        String signed = CredentialKeyRing.ANNOUNCEMENT_CONTEXT
                + "|" + body.path("keyId").asText()
                + "|" + body.path("publicKey").asText()
                + "|" + body.path("expiresAt").asLong();

        boolean ok;
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(RawKeys.ed25519Public(Base64.getUrlDecoder().decode(publicKeyB64)));
            signature.update(signed.getBytes(StandardCharsets.UTF_8));
            ok = signature.verify(Base64.getUrlDecoder().decode(body.path("signature").asText()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A mistyped key, or a signature that is not base64url. This is a tool someone runs at
            // 2am when submits have started failing; a stack trace is the wrong answer to a typo.
            System.out.println("REJECTED — that is not a usable Ed25519 public key, or the "
                    + "signature field is malformed (" + e.getClass().getSimpleName() + ").");
            System.exit(1);
            return;
        }

        if (ok) {
            System.out.println("OK — signed by the pinned identity key. keyId="
                    + body.path("keyId").asText());
        } else {
            System.out.println("REJECTED — this announcement was not signed by that key.");
            System.exit(1);
        }
    }

    private IdentityKeyTool() {}
}
