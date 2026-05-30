package com.github.grepHammerspace.llm;

import com.github.grepHammerspace.db.model.ActivityLog;

import java.util.List;

public record LlmResult(List<ActivityLog> ok, List<LlmParseError> errors) {}
