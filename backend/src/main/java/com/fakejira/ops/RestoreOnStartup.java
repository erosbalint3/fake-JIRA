package com.fakejira.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Finishes a one-click restore: when an admin picked a backup, the app wrote a marker and restarted; here, before
 * the database opens, the current database file, attachments and avatars are moved aside (kept as
 * {@code *.before-restore-<time>}) and the backup's copies put in their place. The outcome is written next to the
 * backups for the admin page. Only the built-in H2 database can be restored this way.
 */
public class RestoreOnStartup implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(RestoreOnStartup.class);
    static final String MARKER = "restore-pending.txt";
    static final String RESULT = "restore-result.txt";
    static final String SOURCE = "restore-source.zip";
    static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024 * 1024;

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();
        apply(Path.of(env.getProperty("app.backup.dir", "./data/backups")),
                env.getProperty("spring.datasource.url", ""),
                Path.of(env.getProperty("app.storage.dir", "./data/attachments")));
    }

    /** Performs a pending restore, if any. Returns true when one was applied. */
    public static boolean apply(Path backupDir, String datasourceUrl, Path attachmentsDir) {
        Path marker = backupDir.resolve(MARKER);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        String name = "";
        try {
            java.util.List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            String file = lines.isEmpty() ? "" : lines.get(0).trim();
            name = lines.size() > 1 ? lines.get(1).trim() : file;
            Path backup = backupDir.resolve(file).normalize();
            if (!backup.startsWith(backupDir.normalize()) || !Files.isRegularFile(backup)) {
                throw new IOException("backup " + name + " not found");
            }
            Path dbFile = databaseFile(datasourceUrl);
            if (dbFile == null) {
                throw new IOException("only the built-in H2 file database can be restored automatically");
            }
            Path newDb = Files.createTempFile(backupDir, "restore-", ".mv.db");
            try (ZipFile zip = new ZipFile(backup.toFile())) {
                ZipEntry database = zip.getEntry("database.zip");
                if (database == null) {
                    throw new IOException("the backup has no database.zip");
                }
                boolean found = false;
                try (ZipInputStream inner = new ZipInputStream(zip.getInputStream(database))) {
                    for (ZipEntry entry; (entry = inner.getNextEntry()) != null; ) {
                        if (entry.getName().endsWith(".mv.db")) {
                            copyLimited(inner, newDb);
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    throw new IOException("database.zip has no .mv.db file");
                }
                // Check every file path before changing anything, so a bad backup leaves the data as it was.
                for (ZipEntry entry : zip.stream().toList()) {
                    String entryName = entry.getName();
                    for (String prefix : new String[]{"attachments/", "avatars/"}) {
                        if (entryName.startsWith(prefix)) {
                            Path base = Path.of("/restore-check").resolve(prefix);
                            if (!base.resolve(entryName.substring(prefix.length())).normalize().startsWith(base)) {
                                throw new IOException("unsafe path in backup: " + entryName);
                            }
                        }
                    }
                }
                // Everything checks out: move the current data aside and put the backup in place.
                Files.createDirectories(dbFile.toAbsolutePath().getParent());
                if (Files.exists(dbFile)) {
                    Files.move(dbFile, dbFile.resolveSibling(dbFile.getFileName() + ".before-restore-" + stamp));
                }
                Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName().toString().replace(".mv.db", ".trace.db")));
                Files.move(newDb, dbFile, StandardCopyOption.REPLACE_EXISTING);
                restoreTree(zip, "attachments/", attachmentsDir, stamp);
                restoreTree(zip, "avatars/", attachmentsDir.resolveSibling("avatars"), stamp);
            } finally {
                Files.deleteIfExists(newDb);
            }
            Files.writeString(backupDir.resolve(RESULT), "ok\n" + name + "\n" + LocalDateTime.now() + "\n");
            log.warn("Restored the database and files from backup {}; the previous data was kept as *.before-restore-{}", name, stamp);
            return true;
        } catch (IOException | RuntimeException e) {
            log.error("Restore from backup {} failed; the current data was left as it was: {}", name, e.getMessage());
            try {
                Files.writeString(backupDir.resolve(RESULT), "failed\n" + name + "\n" + LocalDateTime.now() + "\n" + e.getMessage() + "\n");
            } catch (IOException ignored) {
                // nothing more to do
            }
            return false;
        } finally {
            try {
                Files.deleteIfExists(marker);
                Files.deleteIfExists(backupDir.resolve(SOURCE));
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** The .mv.db file for a jdbc:h2:file: (or plain jdbc:h2:path) URL; null for other databases. */
    static Path databaseFile(String url) {
        if (url == null || !url.startsWith("jdbc:h2:") || url.startsWith("jdbc:h2:mem:") || url.startsWith("jdbc:h2:tcp:")
                || url.startsWith("jdbc:h2:ssl:")) {
            return null;
        }
        String path = url.substring("jdbc:h2:".length());
        if (path.startsWith("file:")) path = path.substring(5);
        int params = path.indexOf(';');
        if (params >= 0) path = path.substring(0, params);
        if (path.startsWith("~")) path = System.getProperty("user.home") + path.substring(1);
        return Path.of(path + ".mv.db");
    }

    private static void restoreTree(ZipFile zip, String prefix, Path target, String stamp) throws IOException {
        boolean any = zip.stream().anyMatch(e -> e.getName().startsWith(prefix) && !e.isDirectory());
        if (Files.exists(target)) {
            Files.move(target, target.resolveSibling(target.getFileName() + ".before-restore-" + stamp));
        }
        Files.createDirectories(target);
        if (!any) {
            return;
        }
        Path root = target.toAbsolutePath().normalize();
        for (ZipEntry entry : zip.stream().filter(e -> e.getName().startsWith(prefix) && !e.isDirectory()).toList()) {
            Path file = root.resolve(entry.getName().substring(prefix.length())).normalize();
            if (!file.startsWith(root)) {
                throw new IOException("unsafe path in backup: " + entry.getName());
            }
            Files.createDirectories(file.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
                copyLimited(in, file);
            }
        }
    }

    private static void copyLimited(InputStream in, Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int n; (n = in.read(buffer)) > 0; ) {
                total += n;
                if (total > MAX_ENTRY_BYTES) {
                    throw new IOException("backup entry too large");
                }
                out.write(buffer, 0, n);
            }
        }
    }
}
