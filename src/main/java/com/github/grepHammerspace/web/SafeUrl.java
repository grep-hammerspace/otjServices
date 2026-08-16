package com.github.grepHammerspace.web;

import okhttp3.HttpUrl;

import java.util.Set;

/**
 * Strips query strings out of URLs before they reach a log line or an exception message.
 *
 * <p>The login chains carry secrets in query parameters. {@code login_hint} is the user's
 * OneAdvanced username, and Keycloak's {@code session_code} / {@code execution} / {@code tab_id}
 * identify a live authentication attempt. Driver exception messages are echoed back to the HTTP
 * caller, so an un-redacted URL leaks to the logs and to the client both.
 *
 * <p>Parameter <em>names</em> survive, because they are what makes a broken flow diagnosable —
 * whether Microsoft returned a {@code code}, or Keycloak dropped the {@code code_challenge}, is
 * the question you actually end up asking — and a name on its own reveals nothing.
 */
final class SafeUrl {

    private static final String NULL_URL = "<null url>";
    private static final String UNPARSEABLE_URL = "<unparseable url>";

    private SafeUrl() {}

    static String redact(String url) {
        if (url == null) return NULL_URL;
        HttpUrl parsed = HttpUrl.parse(url);
        // Deliberately never falls back to the input. A URL we cannot parse is precisely the one
        // we cannot prove is free of secrets.
        return parsed == null ? UNPARSEABLE_URL : redact(parsed);
    }

    static String redact(HttpUrl url) {
        if (url == null) return NULL_URL;
        String withoutQuery = url.newBuilder().query(null).fragment(null).build().toString();
        Set<String> names = url.queryParameterNames();
        return names.isEmpty() ? withoutQuery : withoutQuery + " [" + String.join(", ", names) + "]";
    }
}
