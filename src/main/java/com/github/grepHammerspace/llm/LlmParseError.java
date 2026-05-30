package com.github.grepHammerspace.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmParseError(String error, String message, String raw) {}
