package com.github.grepHammerspace.llm;

public interface LlmService {
    LlmResult parseActivities(String diff, String today, String userId, String learnerId);
}
