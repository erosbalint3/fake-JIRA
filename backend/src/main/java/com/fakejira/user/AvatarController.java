package com.fakejira.user;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Profile pictures. They are served without authentication (so plain img tags work) under
 * random, unguessable names, and only real PNG/JPEG/GIF/WebP images are accepted.
 */
@RestController
public class AvatarController {

    static final long MAX_BYTES = 2 * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("^[0-9a-f]{32}\\.(png|jpg|gif|webp)$");

    private final Path dir;
    private final CurrentUser currentUser;
    private final UserRepository users;

    public AvatarController(@Value("${app.storage.dir:./data/attachments}") String storageDir, CurrentUser currentUser,
                            UserRepository users) {
        this.dir = Path.of(storageDir).toAbsolutePath().normalize().resolveSibling("avatars");
        this.currentUser = currentUser;
        this.users = users;
    }

    @PostMapping("/api/profile/avatar")
    @Transactional
    public UserSummary upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) throws IOException {
        User user = currentUser.from(jwt);
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("Choose an image up to 2 MB.");
        }
        byte[] bytes = file.getBytes();
        String extension = imageType(bytes);
        if (extension == null) {
            throw ApiException.badRequest("Profile pictures must be PNG, JPEG, GIF or WebP images.");
        }
        Files.createDirectories(dir);
        String name = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Files.write(dir.resolve(name), bytes);
        String previous = user.getAvatarName();
        user.setAvatarName(name);
        users.save(user);
        if (previous != null) {
            Files.deleteIfExists(dir.resolve(previous));
        }
        return UserSummary.of(user);
    }

    @DeleteMapping("/api/profile/avatar")
    @Transactional
    public UserSummary remove(@AuthenticationPrincipal Jwt jwt) throws IOException {
        User user = currentUser.from(jwt);
        if (user.getAvatarName() != null) {
            Files.deleteIfExists(dir.resolve(user.getAvatarName()));
            user.setAvatarName(null);
            users.save(user);
        }
        return UserSummary.of(user);
    }

    @GetMapping("/api/avatars/{name}")
    public ResponseEntity<Resource> avatar(@PathVariable String name) {
        if (!NAME.matcher(name).matches() || !Files.isRegularFile(dir.resolve(name))) {
            throw ApiException.notFound("Not found.");
        }
        String extension = name.substring(name.lastIndexOf('.') + 1);
        MediaType type = switch (extension) {
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.IMAGE_JPEG;
        };
        // Names change on every upload, so the file can be cached for a long time.
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(new PathResource(dir.resolve(name)));
    }

    /** Detects the image type from its first bytes; returns null for anything else. */
    static String imageType(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "gif";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
