package com.github.grepHammerspace.api.dto;

/**
 * The outcome of posting pending activities to OneAdvanced.
 *
 * <p>{@code status} is one of {@code nothing_to_post}, {@code ok}, {@code partial} or
 * {@code all_failed}, matching the HTTP status (200, 200, 207, 502) so a client can branch on
 * either. Counts are always present, including as zeroes, so the client never has to test for a
 * missing field.
 */
public record SubmitResponse(String status, int posted, int failed) {}
