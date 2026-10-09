package com.github.grepHammerspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

// `code` is for a client to branch on, and is set only where one does; `error` is for a person.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String code) {
    public ApiError(String error) {
        this(error, null);
    }
}
