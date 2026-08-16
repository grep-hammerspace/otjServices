package com.github.grepHammerspace.admin;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.security.SecureRandom;

/**
 * Generates invite codes of the form {@code OTJ-XXXX-XXXX}.
 *
 * <p>The alphabet drops {@code I}, {@code O}, {@code 0} and {@code 1}: these codes get read down
 * a phone line and typed on a handset keyboard, and those four are the pairs people transcribe
 * wrongly. What is left is 32 symbols, so eight of them carry 40 bits — far past guessing through
 * a single-instance HTTP API, and {@code inviteCodes} has a unique index on {@code code} to catch
 * a collision as a duplicate-key error rather than silently reissuing a live code.
 */
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
