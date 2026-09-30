package com.fakejira.task;

import com.fakejira.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Stores attachment bytes on disk under random names (app.storage.dir). With {@code app.storage.encryption-key}
 * (32 random bytes, base64) new files are encrypted with AES-256-GCM; files are read whether encrypted or not, and
 * {@code app.storage.previous-encryption-keys} lets old keys still decrypt after a key change until
 * {@link #encryptAll()} re-encrypts everything with the current key.
 */
@Component
public class AttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(AttachmentStorage.class);
    static final byte[] MAGIC = "FJENC1".getBytes(StandardCharsets.US_ASCII);
    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;

    /** How many files are encrypted with the current key, with an older key, and not at all. */
    public record EncryptionStatus(boolean enabled, int encrypted, int oldKey, int plain) {
    }

    private final Path root;
    private final SecretKey key;
    private final List<SecretKey> previousKeys;
    private final SecureRandom random = new SecureRandom();

    public AttachmentStorage(@Value("${app.storage.dir:./data/attachments}") String dir,
                             @Value("${app.storage.encryption-key:}") String encryptionKey,
                             @Value("${app.storage.previous-encryption-keys:}") String previousKeys) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.key = encryptionKey.isBlank() ? null : parseKey(encryptionKey, "app.storage.encryption-key");
        this.previousKeys = Arrays.stream(previousKeys.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(k -> parseKey(k, "app.storage.previous-encryption-keys")).toList();
    }

    static SecretKey parseKey(String base64, String property) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(property + " must be base64");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException(property + " must be 32 bytes (256 bits), base64-encoded; e.g. `openssl rand -base64 32`");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public boolean encryptionEnabled() {
        return key != null;
    }

    public String store(MultipartFile file) {
        String name = UUID.randomUUID().toString().replace("-", "");
        try {
            Files.createDirectories(root);
            try (InputStream in = file.getInputStream()) {
                if (key == null) {
                    Files.copy(in, root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.write(root.resolve(name), encrypt(in.readAllBytes(), key));
                }
            }
        } catch (IOException | GeneralSecurityException e) {
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
        try {
            if (!isEncrypted(path)) {
                return new PathResource(path);
            }
            return new ByteArrayResource(decrypt(Files.readAllBytes(path)));
        } catch (IOException | GeneralSecurityException e) {
            log.error("Could not read attachment {}: {}", storageName, e.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "This file is encrypted with a key the server no longer has.");
        }
    }

    public void delete(String storageName) {
        try {
            Files.deleteIfExists(root.resolve(storageName).normalize());
        } catch (IOException e) {
            log.warn("Could not delete attachment {}", storageName, e);
        }
    }

    public EncryptionStatus status() {
        int encrypted = 0;
        int old = 0;
        int plain = 0;
        for (Path path : files()) {
            try {
                if (!isEncrypted(path)) {
                    plain++;
                } else if (key != null && canDecrypt(Files.readAllBytes(path), key)) {
                    encrypted++;
                } else {
                    old++;
                }
            } catch (IOException e) {
                log.warn("Could not inspect {}", path.getFileName());
            }
        }
        return new EncryptionStatus(key != null, encrypted, old, plain);
    }

    /** Encrypts plain files and re-encrypts files from an older key with the current key. Returns how many changed. */
    public synchronized int encryptAll() {
        if (key == null) {
            throw ApiException.badRequest("Set APP_STORAGE_ENCRYPTION_KEY first.");
        }
        int changed = 0;
        for (Path path : files()) {
            try {
                byte[] content = Files.readAllBytes(path);
                byte[] plain;
                if (!startsWithMagic(content)) {
                    plain = content;
                } else if (canDecrypt(content, key)) {
                    continue;
                } else {
                    plain = decrypt(content);
                }
                Path temp = Files.createTempFile(root, ".enc-", ".tmp");
                Files.write(temp, encrypt(plain, key));
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                changed++;
            } catch (IOException | GeneralSecurityException e) {
                log.warn("Could not encrypt {}: {}", path.getFileName(), e.getMessage());
            }
        }
        return changed;
    }

    private List<Path> files() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(root)) {
            return new ArrayList<>(stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith(".")).toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isEncrypted(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return startsWithMagic(in.readNBytes(MAGIC.length));
        }
    }

    private static boolean startsWithMagic(byte[] content) {
        return content.length >= MAGIC.length && Arrays.equals(Arrays.copyOf(content, MAGIC.length), MAGIC);
    }

    byte[] encrypt(byte[] plain, SecretKey with) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, with, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(MAGIC);
        byte[] sealed = cipher.doFinal(plain);
        byte[] out = new byte[MAGIC.length + IV_BYTES + sealed.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(iv, 0, out, MAGIC.length, IV_BYTES);
        System.arraycopy(sealed, 0, out, MAGIC.length + IV_BYTES, sealed.length);
        return out;
    }

    /** Tries the current key, then older ones. */
    byte[] decrypt(byte[] content) throws GeneralSecurityException {
        List<SecretKey> keys = new ArrayList<>();
        if (key != null) keys.add(key);
        keys.addAll(previousKeys);
        GeneralSecurityException last = new AEADBadTagException("No decryption key configured");
        for (SecretKey candidate : keys) {
            try {
                return decryptWith(content, candidate);
            } catch (AEADBadTagException e) {
                last = e;
            }
        }
        throw last;
    }

    private static boolean canDecrypt(byte[] content, SecretKey with) {
        try {
            decryptWith(content, with);
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static byte[] decryptWith(byte[] content, SecretKey with) throws GeneralSecurityException {
        if (content.length < MAGIC.length + IV_BYTES + TAG_BITS / 8) {
            throw new AEADBadTagException("Truncated file");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, with, new GCMParameterSpec(TAG_BITS, content, MAGIC.length, IV_BYTES));
        cipher.updateAAD(MAGIC);
        return cipher.doFinal(content, MAGIC.length + IV_BYTES, content.length - MAGIC.length - IV_BYTES);
    }
}
