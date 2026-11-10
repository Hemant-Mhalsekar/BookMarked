package com.bookmarked.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * {@code app.storage.*}: where private files live and how long file links last.
 * {@code type=local} (default) uses a folder; {@code type=s3} uses an S3-compatible
 * bucket such as Cloudflare R2.
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String type, String localDir, Duration linkTtl, S3 s3) {

    /** For R2: endpoint https://ACCOUNT_ID.r2.cloudflarestorage.com, region "auto". */
    public record S3(String endpoint, String region, String bucket, String accessKey, String secretKey) {
    }

    @ConstructorBinding
    public StorageProperties {
        if (type == null || type.isBlank()) {
            type = "local";
        }
        if (localDir == null || localDir.isBlank()) {
            localDir = "./data/files";
        }
        if (linkTtl == null) {
            linkTtl = Duration.ofMinutes(5);
        }
    }

    public StorageProperties(String localDir, Duration linkTtl) {
        this("local", localDir, linkTtl, null);
    }
}
