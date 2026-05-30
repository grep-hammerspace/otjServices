package com.github.grepHammerspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.grepHammerspace.db.model.ActivityLog;
import com.github.grepHammerspace.llm.LlmParseError;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityLogResponse(
        String status,
        int rowsAdded,
        List<ActivityLog> rows,
        List<LlmParseError> parseErrors
) {}
