package com.bookmarked.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Private files in an S3-compatible bucket (Cloudflare R2 in production). The bucket
 * stays private: nothing is ever served from it directly, the API streams every
 * file after checking a signed link, exactly as with {@link LocalFileStorage}.
 * Keys are validated with the same strict pattern.
 */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3FileStorage implements FileStorage, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

    private final S3Client s3;
    private final String bucket;

    public S3FileStorage(StorageProperties props) {
        this(client(props.s3()), props.s3().bucket());
        log.info("Private file storage in bucket {} at {}", bucket, props.s3().endpoint());
    }

    S3FileStorage(S3Client s3, String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("app.storage.s3.bucket is required when app.storage.type=s3");
        }
        this.s3 = s3;
        this.bucket = bucket;
    }

    static S3Client client(StorageProperties.S3 c) {
        if (c == null || blank(c.endpoint()) || blank(c.accessKey()) || blank(c.secretKey())) {
            throw new IllegalStateException("Set app.storage.s3.endpoint, access-key and secret-key (R2_* env vars)");
        }
        return S3Client.builder()
                .endpointOverride(URI.create(c.endpoint()))
                .region(Region.of(blank(c.region()) ? "auto" : c.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(c.accessKey(), c.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                // only send/verify checksums when an operation needs them: R2 and MinIO don't support every algorithm
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(10))
                        .socketTimeout(Duration.ofSeconds(60)))
                .build();
    }

    @Override
    public void put(String key, Path source) throws IOException {
        check(key);
        try {
            s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/pdf"), RequestBody.fromFile(source));
        } catch (S3Exception e) {
            throw new IOException("Upload failed: " + e.awsErrorDetails().errorMessage(), e);
        }
    }

    @Override
    public void put(String key, byte[] content) throws IOException {
        check(key);
        try {
            s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/pdf"), RequestBody.fromBytes(content));
        } catch (S3Exception e) {
            throw new IOException("Upload failed: " + e.awsErrorDetails().errorMessage(), e);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        check(key);
        try {
            return s3.getObject(b -> b.bucket(bucket).key(key));
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key + ": " + e.statusCode(), e);
        }
    }

    @Override
    public long size(String key) throws IOException {
        check(key);
        try {
            return s3.headObject(b -> b.bucket(bucket).key(key)).contentLength();
        } catch (S3Exception e) {
            throw new IOException("Could not read " + key + ": " + e.statusCode(), e);
        }
    }

    @Override
    public boolean exists(String key) {
        if (key == null) {
            return false;
        }
        check(key);
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        if (key == null) {
            return;
        }
        check(key);
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (S3Exception e) {
            log.warn("Could not delete {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void destroy() {
        s3.close();
    }

    private static void check(String key) {
        if (key == null || !LocalFileStorage.KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
