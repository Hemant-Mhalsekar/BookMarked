package com.bookmarked.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Files in a private folder ({@code app.storage.local-dir}), outside anything a web
 * server serves. Keys must match a strict pattern and resolve inside the folder,
 * so a key can never point somewhere else on the disk.
 */
@Component
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);
    static final Pattern KEY = Pattern.compile("^[a-z]+/[a-f0-9-]{36}\\.pdf$");

    private final Path root;

    public LocalFileStorage(StorageProperties props) {
        this.root = Path.of(props.localDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage folder " + root, e);
        }
        log.info("Private file storage at {}", root);
    }

    @Override
    public void put(String key, Path source) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void put(String key, byte[] content) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public long size(String key) throws IOException {
        return Files.size(resolve(key));
    }

    @Override
    public boolean exists(String key) {
        return key != null && Files.isRegularFile(resolve(key));
    }

    @Override
    public void delete(String key) {
        if (key == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", key, e.getMessage());
        }
    }

    Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }
}
