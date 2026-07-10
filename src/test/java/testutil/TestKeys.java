package testutil;

/** Shared fixed key material for tests. */
public final class TestKeys {

    /** 32 zero bytes, base64-encoded — deterministic AES-256 key for tests only. */
    public static final String PASSWORD_ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private TestKeys() {
    }
}
