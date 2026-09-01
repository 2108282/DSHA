package com.deepseekharness.app;

/** Small dependency-free regression test for {@link DshAuthUrl}. */
public final class AuthUrlTest {

    private static int passed;

    private AuthUrlTest() {
    }

    public static void main(String[] args) {
        String base = DshAuthUrl.LOOPBACK_BASE_URL;
        String token = "A".repeat(43);
        String valid = base + "?token=" + token;

        ParsedCase(valid, "valid URL");
        rejected(base + "?token=a", "short token");

        rejected(null, "null");
        rejected("", "empty");
        rejected(" " + valid, "leading whitespace");
        rejected(valid + " ", "trailing whitespace");
        rejected("HTTP://127.0.0.1:3080/?token=abc", "scheme case/shape");
        rejected("https://127.0.0.1:3080/?token=abc", "https scheme");
        rejected("http://localhost:3080/?token=abc", "localhost alias");
        rejected("http://127.0.0.2:3080/?token=abc", "non-loopback host");
        rejected("http://127.0.0.1:3081/?token=abc", "wrong port");
        rejected("http://127.0.0.1/?token=abc", "implicit port");
        rejected("http://127.0.0.1:03080/?token=abc", "non-canonical port");
        rejected("http://127.0.0.1:3080?token=abc", "missing slash path");
        rejected("http://127.0.0.1:3080//?token=abc", "extra path slash");
        rejected("http://127.0.0.1:3080/?token=", "empty token");
        rejected("http://127.0.0.1:3080/?token=abc&x=1", "extra query");
        rejected("http://127.0.0.1:3080/?x=1&token=abc", "query order/extra key");
        rejected("http://127.0.0.1:3080/?token=abc&token=def", "duplicate token");
        rejected("http://127.0.0.1:3080/?token=abc#fragment", "fragment");
        rejected("http://127.0.0.1:3080/?to%6ben=abc", "encoded parameter name");
        rejected("http://127.0.0.1:3080/?token=%61bc", "encoded token");
        rejected("http://127.0.0.1:3080/?token=abc%2Ddef", "encoded token punctuation");
        rejected("http://127.0.0.1:3080/?token=abc+def", "form plus token");
        rejected("http://127.0.0.1:3080/?token=abc/def", "base64 (not base64url) token");
        rejected("http://127.0.0.1:3080/?token=abc=", "padded token");
        rejected("http://user@127.0.0.1:3080/?token=abc", "userinfo");
        rejected("http://127.0.0.1:3080/?token=abc\r\nX-Evil: yes", "line injection");

        DshAuthUrl.Parsed parsed = DshAuthUrl.require(valid);
        eq("require preserves auth URL", valid, parsed.authUrl);
        eq("require preserves base URL", base, parsed.loopbackBaseUrl);
        eq("require preserves token", token, parsed.token);

        ParsedStartup("dsh web: " + valid + "\n", "official startup line");
        ParsedStartup("noise\ndsh web: " + valid + " (LAN: ignored)\n",
                "official line with diagnostic suffix");
        rejectedStartup("noise dsh web: " + valid + "\n", "embedded startup marker");
        rejectedStartup("dsh web: http://127.0.0.1:3080/?token=short\n",
                "malformed startup URL");
        rejectedStartup("dsh web: " + valid + "\n"
                        + "dsh web: " + base + "?token=" + "B".repeat(43) + "\n",
                "conflicting startup URLs");
        try {
            DshAuthUrl.require("http://127.0.0.1:3080/?token=%61bc");
            throw new AssertionError("require accepted malformed URL");
        } catch (IllegalArgumentException expected) {
            // Deliberately do not inspect the exception text for the token.
            passed++;
        }

        System.out.println("auth-url-test: " + passed + " passed");
    }

    private static void ParsedCase(String url, String name) {
        DshAuthUrl.Parsed parsed = DshAuthUrl.parse(url);
        if (parsed == null) throw new AssertionError(name + ": rejected valid URL");
        eq(name + ": auth URL", url, parsed.authUrl);
        passed++;
    }

    private static void rejected(String url, String name) {
        if (DshAuthUrl.parse(url) != null) {
            throw new AssertionError(name + ": accepted malformed URL");
        }
        passed++;
    }

    private static void ParsedStartup(String output, String name) {
        DshAuthUrl.Parsed parsed = DshAuthUrl.fromStartupOutput(output);
        if (parsed == null) throw new AssertionError(name + ": rejected valid startup output");
        passed++;
    }

    private static void rejectedStartup(String output, String name) {
        if (DshAuthUrl.fromStartupOutput(output) != null) {
            throw new AssertionError(name + ": accepted malformed startup output");
        }
        passed++;
    }

    private static void eq(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
    }
}
