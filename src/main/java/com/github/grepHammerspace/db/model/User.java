package com.github.grepHammerspace.db.model;

public record User(
        String userId,
        String username,
        String password,
        String learnerId
) {}
