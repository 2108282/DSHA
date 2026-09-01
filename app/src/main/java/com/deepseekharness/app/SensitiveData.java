package com.deepseekharness.app;

/** Android-free redaction for diagnostics that may contain credentials. */
public final class SensitiveData {
    /**
     * Chunk-safe redaction for long-running process output.  dsh writes one
     * logical diagnostic line in arbitrary read() chunks, so callers must not
     * redact each chunk independently: a credential can straddle the boundary.
     * Complete lines are emitted only after their newline arrives; an
     * unterminated oversized line is dropped rather than risking a raw suffix.
     */
    public static final class Stream {
        private static final int MAX_PENDING = 64 * 1024;
        private String pending = "";

        /** Return redacted complete lines made available by this chunk. */
        public String accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) return "";
            pending += chunk;
            int lastNewline = pending.lastIndexOf('\n');
            if (lastNewline < 0) {
                if (pending.length() > MAX_PENDING) {
                    pending = "";
                    return "[dsh output omitted: unterminated line]\n";
                }
                return "";
            }
            String complete = pending.substring(0, lastNewline + 1);
            pending = pending.substring(lastNewline + 1);
            return redact(complete);
        }

        /** Flush the final unterminated line when the process exits. */
        public String finish() {
            String out = redact(pending);
            pending = "";
            return out;
        }
    }

    private SensitiveData() {
    }

    /**
     * Redact URL tokens, DSHA/dsh cookies, token headers, and API-key values.
     * Diagnostics may still mention the field name, but never its value.
     */
    public static String redact(String value) {
        if (value == null || value.isEmpty()) return value;
        String out = value;
        // Query values can be percent encoded, so stop only at a query/fragment
        // separator rather than trying to maintain an allow-list of token bytes.
        out = out.replaceAll("(?i)([?&](?:token|dsha_t|dsha_token|api[_-]?key)=)[^&#\\s]+",
                "$1<redacted>");
        // dsh may emit structured diagnostics (for example JSON errors).  The
        // plain key=value rules below intentionally do not match a quoted JSON
        // key, so handle quoted sensitive fields before the line-oriented rules.
        // The value matcher accepts escaped JSON characters while retaining the
        // surrounding quotes and object delimiters.
        out = out.replaceAll(
                "(?i)([\"'](?:cookie|set-cookie|authorization|x-dsha-token|x-token"
                        + "|dsha_lan|dsha_t|dsha_token|dsh-auth-[A-Za-z0-9_-]+"
                        + "|token|api[_-]?key|deepseek_api_key)[\"']\\s*:\\s*[\"'])"
                        + "((?:\\\\.|[^\"\\\\\\r\\n])*)([\"'])",
                "$1<redacted>$3");
        // A diagnostic must never carry a usable browser/session cookie. Redact
        // the entire Cookie or Set-Cookie value because one line can have more
        // than one credential.
        out = out.replaceAll("(?im)(\\b(?:cookie|set-cookie)\\s*[:=]\\s*)[^\\r\\n]+",
                "$1<redacted>");
        out = out.replaceAll("(?i)(\\b(?:dsha_lan|dsha_t|dsha_token|dsh-auth-[A-Za-z0-9_-]+)\\s*=)\\s*[^;\\s\\r\\n]+",
                "$1<redacted>");
        out = out.replaceAll("(?im)(\\b(?:x-dsha-token|x-token|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\r\\n]+",
                "$1<redacted>");
        out = out.replaceAll("(?i)(\\b(?:DEEPSEEK_API_KEY|api[_-]?key|token|dsha_t|dsha_token)\\s*[=:]\\s*)[^\\s\\r\\n,;]+",
                "$1<redacted>");
        out = out.replaceAll("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/%=-]+", "$1<redacted>");
        return out;
    }
}
