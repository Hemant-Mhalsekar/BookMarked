package com.bookmarked.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP and user agent of the caller. With {@code server.forward-headers-strategy=framework}
 * the remote address already reflects X-Forwarded-For from a trusted proxy.
 */
public record ClientInfo(String ipAddress, String userAgent) {

    public static ClientInfo from(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua != null && ua.length() > 300) {
            ua = ua.substring(0, 300);
        }
        return new ClientInfo(request.getRemoteAddr(), ua);
    }
}
