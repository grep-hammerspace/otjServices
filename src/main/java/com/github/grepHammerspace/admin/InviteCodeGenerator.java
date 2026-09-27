package com.github.grepHammerspace.admin;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.security.SecureRandom;

// No I, O, 0 or 1: codes are read aloud and typed on phones. 32 symbols x 8 = 40 bits.
@Singleton
public class InviteCodeGenerator {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int GROUP_LENGTH = 4;

    private final SecureRandom random = new SecureRandom();

    @Inject
    public InviteCodeGenerator() {}

    public String generate() {
        return "OTJ-" + group() + "-" + group();
    }

    private String group() {
        StringBuilder builder = new StringBuilder(GROUP_LENGTH);
        for (int i = 0; i < GROUP_LENGTH; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
