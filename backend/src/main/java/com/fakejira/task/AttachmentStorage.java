package com.fakejira.task;

import com.fakejira.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Stores attachment bytes on disk under random names (app.storage.dir). */
@Component
public class AttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(AttachmentStorage.class);

    private final Path root;

    public AttachmentStorage(@Value("${app.storage.dir:./data/attachments}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file) {
        String name = UUID.randomUUID().toString().replace("-", "");
        try {
            Files.createDirectories(root);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Could not store attachment", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the file.");
        }
        return name;
    }

    public Resource load(String storageName) {
        Path path = root.resolve(storageName).normalize();
        if (!path.startsWith(root) || !Files.isReadable(path)) {
            throw ApiException.notFound("File not found.");
        }
        return new PathResource(path);
    }

    public void delete(String storageName) {
        try {
            Files.deleteIfExists(root.resolve(storageName).normalize());
        } catch (IOException e) {
            log.warn("Could not delete attachment {}", storageName, e);
        }
    }
}
