package com.bookmarked.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code app.storage.*}: where private files live and how long file links last. */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String localDir, Duration linkTtl) {

    public StorageProperties {
        if (localDir == null || localDir.isBlank()) {
            localDir = "./data/files";
        }
        if (linkTtl == null) {
            linkTtl = Duration.ofMinutes(5);
        }
    }
}
