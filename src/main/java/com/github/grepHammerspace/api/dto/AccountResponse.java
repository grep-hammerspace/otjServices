package com.github.grepHammerspace.api.dto;

import com.github.grepHammerspace.db.model.User;

/**
 * The signed-in account as the client sees it.
 *
 * <p>Deliberately narrower than {@link User}, and the only thing that should ever be serialised in
 * its place. {@code appPasswordHash} is omitted for the obvious reason; {@code userId} because it
 * is a server-minted identifier the client has no use for — every endpoint takes the caller from
 * the token, so it appears in no URL — and {@code createdAt} because nothing asks for it.
 *
 * <p>{@code appUsername} is renamed to {@code username} on the way out. That name exists to stop
 * <em>server-side</em> confusion with the OneAdvanced username, which never reaches this
 * application at all; the client has only ever had one username of its own.
 */
public record AccountResponse(String username, String learnerId) {

    public static AccountResponse from(User user) {
        return new AccountResponse(user.appUsername(), user.learnerId());
    }
}
