package com.github.grepHammerspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.grepHammerspace.llm.ParsedActivities;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityLogResponse(
        String status,
        int rowsAdded,
        List<PendingActivity> rows,
        List<ParsedActivities.ParseError> parseErrors
) {}
