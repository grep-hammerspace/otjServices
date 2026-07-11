package com.github.grepHammerspace.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KsbMatch(String activityDate, String activitySummary, List<String> matchedKsbs, String explanation) {}
