package com.github.grepHammerspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.grepHammerspace.llm.LlmParseError;

import java.util.List;

/** Body of {@code POST /otj-services/log-activities}.
 *
 * <p>{@code rows} carries {@link PendingActivity}, not raw {@code ActivityLog} records. The raw
 * record includes {@code tailscaleUserId}, and AGENTS.md's rule is that the client never receives
 * the server-minted userId. Using the same DTO as {@code GET /pending} also means the rows come
 * back with their ids, so a client can delete one it just added without refetching the list.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityLogResponse(
        String status,
        int rowsAdded,
        List<PendingActivity> rows,
        List<LlmParseError> parseErrors
) {}
