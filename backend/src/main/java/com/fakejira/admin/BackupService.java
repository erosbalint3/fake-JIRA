package com.fakejira.admin;

import com.fakejira.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a single zip with a consistent online H2 backup plus all attachments.
 * Runs on a schedule (app.backup.cron) and on demand from the admin page.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern NAME = Pattern.compile("^fakejira-backup-\\d{8}-\\d{6}\\.zip$");

    public record BackupFile(String name, long size, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final Path backupDir;
    private final Path attachmentsDir;
    private final int keep;
    private final String datasourceUrl;

    public BackupService(JdbcTemplate jdbc,
                         @Value("${app.backup.dir:./data/backups}") String backupDir,
                         @Value("${app.storage.dir:./data/attachments}") String attachmentsDir,
                         @Value("${app.backup.keep:14}") int keep,
                         @Value("${spring.datasource.url}") String datasourceUrl) {
        this.jdbc = jdbc;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.attachmentsDir = Path.of(attachmentsDir).toAbsolutePath().normalize();
        this.keep = Math.max(1, keep);
        this.datasourceUrl = datasourceUrl;
    }

    @Scheduled(cron = "${app.backup.cron:0 30 3 * * *}")
    public void scheduled() {
        try {
            BackupFile file = create();
            log.info("Backup written: {} ({} bytes)", file.name(), file.size());
        } catch (RuntimeException e) {
            log.error("Scheduled backup failed", e);
        }
    }

    public synchronized BackupFile create() {
        String name = "fakejira-backup-" + STAMP.format(LocalDateTime.now()) + ".zip";
        Path target = backupDir.resolve(name);
        Path dbDump = null;
        String dbEntry = null;
        try {
            Files.createDirectories(backupDir);
            if (datasourceUrl.startsWith("jdbc:h2:mem:")) {
                // In-memory databases (tests) cannot use BACKUP; dump them as SQL instead.
                dbDump = Files.createTempFile(backupDir, "db-", ".sql");
                jdbc.execute("SCRIPT TO '" + dbDump.toString().replace("'", "''") + "'");
                dbEntry = "database.sql";
            } else if (datasourceUrl.startsWith("jdbc:h2:")) {
                dbDump = Files.createTempFile(backupDir, "db-", ".zip");
                Files.delete(dbDump);
                // BACKUP TO takes a consistent snapshot while the app keeps running.
                jdbc.execute("BACKUP TO '" + dbDump.toString().replace("'", "''") + "'");
                dbEntry = "database.zip";
            }
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
                if (dbDump != null) {
                    addFile(zip, dbDump, dbEntry);
                }
                if (Files.isDirectory(attachmentsDir)) {
                    try (Stream<Path> files = Files.walk(attachmentsDir)) {
                        for (Path file : files.filter(Files::isRegularFile).toList()) {
                            addFile(zip, file, "attachments/" + attachmentsDir.relativize(file));
                        }
                    }
                }
                zip.putNextEntry(new ZipEntry("README.txt"));
                zip.write(("FakeJIRA backup " + name + "\n\nRestore: stop the app, unzip database.zip into the data folder"
                        + " (fakejira.mv.db) and copy attachments/ back to the attachments folder, then start the app.\n")
                        .getBytes());
                zip.closeEntry();
            }
            prune();
            return describe(target);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Backup failed: " + e.getMessage());
        } finally {
            if (dbDump != null) {
                try {
                    Files.deleteIfExists(dbDump);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    public List<BackupFile> list() {
        if (!Files.isDirectory(backupDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(backupDir)) {
            return files.filter(p -> NAME.matcher(p.getFileName().toString()).matches())
                    .map(this::describe)
                    .sorted(Comparator.comparing(BackupFile::name).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public Path file(String name) {
        if (!NAME.matcher(name).matches()) {
            throw ApiException.notFound("Backup not found.");
        }
        Path path = backupDir.resolve(name);
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("Backup not found.");
        }
        return path;
    }

    private void prune() {
        List<BackupFile> all = list();
        for (BackupFile old : all.subList(Math.min(keep, all.size()), all.size())) {
            try {
                Files.deleteIfExists(backupDir.resolve(old.name()));
            } catch (IOException e) {
                log.warn("Could not delete old backup {}", old.name());
            }
        }
    }

    private BackupFile describe(Path path) {
        try {
            return new BackupFile(path.getFileName().toString(), Files.size(path),
                    Files.getLastModifiedTime(path).toInstant());
        } catch (IOException e) {
            return new BackupFile(path.getFileName().toString(), 0, Instant.EPOCH);
        }
    }

    private static void addFile(ZipOutputStream zip, Path file, String entryName) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        try (OutputStream out = new NonClosing(zip)) {
            Files.copy(file, out);
        }
        zip.closeEntry();
    }

    /** Lets Files.copy write into the zip without closing it. */
    private static final class NonClosing extends java.io.FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
