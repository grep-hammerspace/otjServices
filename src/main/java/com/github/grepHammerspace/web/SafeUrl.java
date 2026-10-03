package com.github.grepHammerspace.web;

import okhttp3.HttpUrl;

import java.util.Set;

// Strips query strings before a URL reaches a log line or an exception message: login_hint is the
// username, and Keycloak's parameters identify a live attempt. Parameter names survive.
final class SafeUrl {
    private static final String NULL_URL = "<null url>";
    private static final String UNPARSEABLE_URL = "<unparseable url>";

    private SafeUrl() {}

    static String redact(String url) {
        if (url == null) return NULL_URL;
        HttpUrl parsed = HttpUrl.parse(url);
        // Never falls back to the input: an unparseable URL can't be proven secret-free.
        return parsed == null ? UNPARSEABLE_URL : redact(parsed);
    }

    static String redact(HttpUrl url) {
        if (url == null) return NULL_URL;
        String withoutQuery = url.newBuilder().query(null).fragment(null).build().toString();
        Set<String> names = url.queryParameterNames();
        return names.isEmpty() ? withoutQuery : withoutQuery + " [" + String.join(", ", names) + "]";
    }
}
