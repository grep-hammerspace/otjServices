package com.github.grepHammerspace.api.dto;

/**
 * Request body for {@code PATCH /auth/me}.
 *
 * <p>Carries no {@code @NotBlank}, unlike {@link SignupRequest}. The resource hand-checks the
 * field instead, because a bean-validation violation does not use the {@code {"error": "..."}}
 * shape the mobile client reads its messages out of — it would reach the user as a generic
 * "please check the details you entered" and the specific reason would be lost.
 *
 * <p>A missing key therefore deserialises to {@code null} and is rejected by that check, with the
 * same message a blank string gets.
 */
public record UpdateLearnerIdRequest(String learnerId) {}
