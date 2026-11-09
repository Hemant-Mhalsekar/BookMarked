package com.bookmarked.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageTest {

    @TempDir
    Path dir;

    @Test
    void stores_and_reads_back_inside_its_folder() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(new StorageProperties(dir.toString(), Duration.ofMinutes(5)));
        String key = "ebooks/3f2c6a1e-8a51-4d55-9a0e-2b8f6f0c1d2e.pdf";
        storage.put(key, "%PDF-1.7 test".getBytes());
        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.size(key)).isEqualTo(13);
        try (var in = storage.open(key)) {
            assertThat(new String(in.readAllBytes())).startsWith("%PDF-");
        }
        assertThat(storage.resolve(key)).startsWith(dir.toAbsolutePath().normalize());
        storage.delete(key);
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void refuses_keys_that_could_escape_the_folder() {
        LocalFileStorage storage = new LocalFileStorage(new StorageProperties(dir.toString(), Duration.ofMinutes(5)));
        for (String bad : new String[]{"../etc/passwd", "ebooks/../../x.pdf", "/etc/passwd", "ebooks/abc.pdf",
                "ebooks/3f2c6a1e-8a51-4d55-9a0e-2b8f6f0c1d2e.exe", "EBOOKS/3f2c6a1e-8a51-4d55-9a0e-2b8f6f0c1d2e.pdf", ""}) {
            assertThatThrownBy(() -> storage.resolve(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(storage.exists(null)).isFalse();
    }
}
