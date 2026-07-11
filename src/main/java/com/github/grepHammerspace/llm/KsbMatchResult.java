package com.github.grepHammerspace.llm;

import java.util.List;

public record KsbMatchResult(List<KsbMatch> matches, int totalActivitiesConsidered) {}
