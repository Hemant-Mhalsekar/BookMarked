package com.bookmarked.storage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The R2 storage against Adobe S3Mock, an open-source server that speaks the same S3 API. */
@Testcontainers
class S3FileStorageTest {

    @Container
    static GenericContainer<?> s3mock = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0"))
            .withExposedPorts(9090)
            .waitingFor(Wait.forListeningPort());

    static S3Client client;
    static S3FileStorage storage;

    @TempDir
    Path dir;

    @BeforeAll
    static void setUp() {
        String endpoint = "http://" + s3mock.getHost() + ":" + s3mock.getMappedPort(9090);
        client = S3FileStorage.client(new StorageProperties.S3(endpoint, "auto", "bookmarked-test",
                "test-access-key", "test-secret-key"));
        client.createBucket(b -> b.bucket("bookmarked-test"));
        storage = new S3FileStorage(client, "bookmarked-test");
    }

    @AfterAll
    static void tearDown() {
        storage.destroy();
    }

    @Test
    void stores_reads_sizes_and_deletes() throws Exception {
        String key = "ebooks/3f2c6a1e-8a51-4d55-9a0e-2b8f6f0c1d2e.pdf";
        assertThat(storage.exists(key)).isFalse();
        storage.put(key, "%PDF-1.7 test".getBytes());
        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.size(key)).isEqualTo(13);
        try (var in = storage.open(key)) {
            assertThat(new String(in.readAllBytes())).startsWith("%PDF-");
        }

        Path file = dir.resolve("big.pdf");
        Files.write(file, new byte[3 * 1024 * 1024]);
        String other = "samples/0b1c2d3e-4f50-4a61-8b72-9c8d7e6f5a4b.pdf";
        storage.put(other, file);
        assertThat(storage.size(other)).isEqualTo(3 * 1024 * 1024);

        storage.delete(key);
        assertThat(storage.exists(key)).isFalse();
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IOException.class);
        storage.delete(key);   // deleting twice is fine
    }

    @Test
    void refuses_keys_outside_the_pattern() {
        for (String bad : new String[]{"../x.pdf", "ebooks/abc.pdf", "/ebooks/3f2c6a1e-8a51-4d55-9a0e-2b8f6f0c1d2e.pdf", ""}) {
            assertThatThrownBy(() -> storage.exists(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(storage.exists(null)).isFalse();
    }
}
