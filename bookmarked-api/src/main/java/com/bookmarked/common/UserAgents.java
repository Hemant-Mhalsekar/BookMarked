package com.bookmarked.common;

/**
 * Turns a raw User-Agent into "Chrome on macOS" for session lists and alerts.
 * Deliberately small: good enough for humans, not for analytics.
 */
public final class UserAgents {

    private UserAgents() {
    }

    public static String describe(String ua) {
        if (ua == null || ua.isBlank()) {
            return "Unknown device";
        }
        return browser(ua) + " on " + os(ua);
    }

    private static String browser(String ua) {
        if (ua.contains("Edg/")) return "Edge";
        if (ua.contains("OPR/") || ua.contains("Opera")) return "Opera";
        if (ua.contains("SamsungBrowser")) return "Samsung Internet";
        if (ua.contains("Firefox/") || ua.contains("FxiOS")) return "Firefox";
        if (ua.contains("Chrome/") || ua.contains("CriOS")) return "Chrome";
        if (ua.contains("Safari/")) return "Safari";
        if (ua.contains("PostmanRuntime")) return "Postman";
        if (ua.startsWith("curl/")) return "curl";
        return "Browser";
    }

    private static String os(String ua) {
        if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Mac OS X") || ua.contains("Macintosh")) return "macOS";
        if (ua.contains("CrOS")) return "ChromeOS";
        if (ua.contains("Linux")) return "Linux";
        return "unknown OS";
    }
}
