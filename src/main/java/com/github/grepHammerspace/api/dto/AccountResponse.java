package com.github.grepHammerspace.api.dto;

import com.github.grepHammerspace.db.model.User;

// Deliberately narrower than User; never serialise User itself.
public record AccountResponse(String username, String learnerId) {
    public static AccountResponse from(User user) {
        return new AccountResponse(user.appUsername(), user.learnerId());
    }
}
