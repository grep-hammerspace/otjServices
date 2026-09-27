package com.github.grepHammerspace.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

// RFC 5869 appendix A, SHA-256 cases, checked on the first 32 bytes: T(1) doesn't depend on later blocks.
class HkdfTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    @DisplayName("Case 1 — basic")
    void caseOne() throws Exception {
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf",
                HEX.formatHex(Hkdf.derive(
                        HEX.parseHex("0b".repeat(22)),
                        HEX.parseHex("000102030405060708090a0b0c"),
                        HEX.parseHex("f0f1f2f3f4f5f6f7f8f9"))));
    }

    @Test
    @DisplayName("Case 2 — longer inputs than one hash block")
    void caseTwo() throws Exception {
        assertEquals("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c",
                HEX.formatHex(Hkdf.derive(range(0x00, 0x50), range(0x60, 0xb0), range(0xb0, 0x100))));
    }

    @Test
    @DisplayName("Case 3 — no salt, no info")
    void caseThree() throws Exception {
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d",
                HEX.formatHex(Hkdf.derive(HEX.parseHex("0b".repeat(22)), new byte[0], new byte[0])));
    }

    private static byte[] range(int fromInclusive, int toExclusive) {
        byte[] bytes = new byte[toExclusive - fromInclusive];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (fromInclusive + i);
        return bytes;
    }
}
