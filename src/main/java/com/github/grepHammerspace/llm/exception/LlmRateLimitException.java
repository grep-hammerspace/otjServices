package com.github.grepHammerspace.llm.exception;

public class LlmRateLimitException extends LlmException {
    public LlmRateLimitException(String message, Throwable cause) { super(message, cause); }
}
