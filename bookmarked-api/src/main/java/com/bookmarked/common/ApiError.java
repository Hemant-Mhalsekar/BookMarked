package com.bookmarked.common;

import java.time.Instant;
import java.util.Map;

/**
 * The one error shape every endpoint returns.
 */
public record ApiError(
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors,
        String path,
        Instant timestamp
) {
    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(status, error, message, null, path, Instant.now());
    }
}
