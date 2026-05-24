package com.github.grepHammerspace.sessions;

import java.util.UUID;
import  java.util.concurrent.ConcurrentHashMap;
/**
 * Simple ConcurrentHashMap-based session store. It will be used to maintain active sessions
 */

/**
 * Requirements
 * Sessions must expire after a configurable period of inactivity
 * Sessions must expire after a configurable maximum lifetime regardless of activity
 * A user should only be able to have one active session at a time — creating a new session should invalidate the old one
 * The store must be thread-safe to handle concurrent virtual thread access
 * Expired sessions must be cleaned up periodically to prevent unbounded memory growth
 * Session lookup should update the last activity timestamp
 * The store should expose a way to invalidate a session explicitly for logout
 * Session tokens must be cryptographically random and sufficiently long to prevent guessing
 * The store should be able to report how many active sessions exist at any given time
 */
public class SessionStore {

    private ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    public boolean isValid (UUID userSessionKey){
        return  (sessions.containsKey(userSessionKey)) ? true : false;
    }

    public UUID addSession(){
        return null;
    }
}
