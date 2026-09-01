package com.deepseekharness.app;

import java.util.regex.Pattern;

/**
 * Strict parser for the URL printed by the dsh 1.2 BrowserAuth startup path.
 *
 * <p>The value is deliberately parsed as an opaque, already-serialized URL.  We
 * do not URL-decode it: accepting an encoded parameter name/value would make it
 * possible to authenticate a different URL than the one dsh printed.  The
 * parser also does not accept a host or port supplied by a caller; dsh is
 * required to stay on the fixed loopback endpoint.
 */
public final class DshAuthUrl {

    /** The only authority accepted for the local dsh Web profile. */
    public static final String LOOPBACK_BASE_URL = "http://127.0.0.1:3080/";
    /** Prefix of a complete BrowserAuth launch URL. */
    public static final String AUTH_URL_PREFIX = LOOPBACK_BASE_URL + "?token=";

    /* dsh uses Buffer.toString('base64url') for its process token. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    /** Official output is a complete line; do not accept an embedded marker. */
    private static final Pattern STARTUP = Pattern.compile(
            "^dsh web: (\\S+)(?:[ \\t][^\\r\\n]*)?$", Pattern.MULTILINE);

    private DshAuthUrl() {
    }

    /**
     * Parsed immutable launch information.
     *
     * <p>Fields are public and final so Android callers can pass the object
     * between the controller and a Fragment without another mutable holder.
     * The token is intentionally never written to disk or to logs by this
     * class.
     */
    public static final class Parsed {
        public final String loopbackBaseUrl;
        public final String authUrl;
        public final String token;

        private Parsed(String token) {
            this.loopbackBaseUrl = LOOPBACK_BASE_URL;
            this.token = token;
            this.authUrl = AUTH_URL_PREFIX + token;
        }
    }

    /**
     * Parse one exact dsh startup URL, returning {@code null} on any mismatch.
     *
     * <p>The accepted grammar is exactly
     * {@code http://127.0.0.1:3080/?token=<base64url-token>}.  In particular,
     * there must be one query parameter and no fragment.  Keeping this as an
     * exact ASCII grammar avoids URI normalization, percent-decoding, or
     * default-port behavior changing the security boundary.
     */
    public static Parsed parse(String candidate) {
        if (candidate == null || candidate.isEmpty()) return null;
        // Do not silently normalize output copied from a process line.  The
        // caller must first extract the URL token from that line explicitly.
        if (!candidate.equals(candidate.trim())) return null;
        if (!candidate.startsWith(AUTH_URL_PREFIX)) return null;

        String token = candidate.substring(AUTH_URL_PREFIX.length());
        if (token.isEmpty() || !TOKEN.matcher(token).matches()) return null;

        // startsWith + the unreserved-token grammar already excludes '?', '&',
        // '#', '%', user-info, encoded names, and all extra query parameters.
        return new Parsed(token);
    }

    /** Extract and validate the URL from official dsh stdout/stderr. */
    public static Parsed fromStartupOutput(String output) {
        if (output == null || output.isEmpty()) return null;
        java.util.regex.Matcher matcher = STARTUP.matcher(output);
        Parsed found = null;
        while (matcher.find()) {
            Parsed parsed = parse(matcher.group(1));
            // A startup marker with a malformed URL is a launch failure, even
            // if a later line happens to contain a valid-looking URL.
            if (parsed == null) return null;
            if (found == null) {
                found = parsed;
            } else if (!found.authUrl.equals(parsed.authUrl)) {
                // One dsh generation has one BrowserAuth URL. Conflicting
                // repeats are ambiguous and must fail closed.
                return null;
            }
        }
        return found;
    }

    /**
     * Parse or throw a generic error that never includes the credential.
     * Useful for startup paths where malformed dsh output must be reported as
     * a launch failure instead of being treated as a URL guess.
     */
    public static Parsed require(String candidate) {
        Parsed parsed = parse(candidate);
        if (parsed == null) {
            throw new IllegalArgumentException("invalid dsh BrowserAuth URL");
        }
        return parsed;
    }
}
