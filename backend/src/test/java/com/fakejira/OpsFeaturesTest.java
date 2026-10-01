package com.fakejira;

import com.fakejira.ops.RestoreOnStartup;
import com.fakejira.task.AttachmentStorage;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Data retention, attachment encryption at rest, one-click restore, and health alerts. */
@SpringBootTest
@AutoConfigureMockMvc
class OpsFeaturesTest extends ApiTestSupport {

    static final Path STORAGE;
    static final String KEY = key();

    static {
        try {
            STORAGE = Files.createTempDirectory("fj-ops-attachments");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.dir", STORAGE::toString);
        registry.add("app.storage.encryption-key", () -> KEY);
        registry.add("app.backup.dir", () -> STORAGE.resolveSibling(STORAGE.getFileName() + "-backups").toString());
    }

    @Autowired
    TransactionTemplate tx;

    @Autowired
    com.fakejira.user.UserRepository users;

    @Autowired
    com.fakejira.task.TaskRepository tasks;

    private Account admin() throws Exception {
        Account account = register();
        tx.executeWithoutResult(s -> users.findById(account.id()).orElseThrow().setAdmin(true));
        return account;
    }

    private String j(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    // ---- Retention ------------------------------------------------------------------------------------------------

    @Test
    void retentionArchivesAndDeletesOldWork() throws Exception {
        Account admin = admin();
        String key = project(admin);
        long oldDone = task(admin, key, "Finished long ago").get("id").asLong();
        long recentDone = task(admin, key, "Finished yesterday").get("id").asLong();
        long longArchived = task(admin, key, "Archived ages ago").get("id").asLong();
        long subtask = task(admin, key, "Its subtask", "parentId", longArchived).get("id").asLong();
        long open = task(admin, key, "Still open").get("id").asLong();
        perform(post("/api/tasks/" + open + "/comments"), admin, body("body", "keep me")).andExpect(status().isCreated());
        Instant now = Instant.now();
        tx.executeWithoutResult(s -> {
            var a = tasks.findById(oldDone).orElseThrow();
            a.setStatus(com.fakejira.task.TaskStatus.DONE);
            var b = tasks.findById(recentDone).orElseThrow();
            b.setStatus(com.fakejira.task.TaskStatus.DONE);
            tasks.findById(longArchived).orElseThrow().setArchivedAt(now.minus(400, ChronoUnit.DAYS));
        });
        tx.executeWithoutResult(s -> {
            org.springframework.test.util.ReflectionTestUtils.setField(tasks.findById(oldDone).orElseThrow(), "completedAt",
                    now.minus(100, ChronoUnit.DAYS));
            org.springframework.test.util.ReflectionTestUtils.setField(tasks.findById(recentDone).orElseThrow(), "completedAt",
                    now.minus(1, ChronoUnit.DAYS));
        });

        JsonNode initial = getJson("/api/admin/retention", admin);
        assertThat(initial.get("policy").get("archiveDoneAfterDays").asInt()).isZero();
        assertThat(initial.get("policy").get("deleteAuditAfterDays").asInt()).isEqualTo(365);
        perform(put("/api/admin/retention"), admin, j(Map.of("archiveDoneAfterDays", 30, "deleteArchivedAfterDays", 10,
                "deleteNotificationsAfterDays", 0, "deleteAuditAfterDays", 365))).andExpect(status().isBadRequest());
        JsonNode saved = read(perform(put("/api/admin/retention"), admin, j(Map.of("archiveDoneAfterDays", 30,
                "deleteArchivedAfterDays", 365, "deleteNotificationsAfterDays", 90, "deleteAuditAfterDays", 365)))
                .andExpect(status().isOk()));
        assertThat(saved.get("preview").get("tasksArchived").asInt()).isEqualTo(1);
        assertThat(saved.get("preview").get("tasksDeleted").asInt()).isEqualTo(1);
        perform(get("/api/admin/retention"), register()).andExpect(status().isForbidden());

        JsonNode ran = read(perform(post("/api/admin/retention/run"), admin).andExpect(status().isOk()));
        assertThat(ran.get("tasksArchived").asInt()).isEqualTo(1);
        assertThat(ran.get("tasksDeleted").asInt()).isEqualTo(1);
        assertThat(getJson("/api/tasks/" + oldDone, admin).get("archivedAt").isNull()).isFalse();
        assertThat(getJson("/api/tasks/" + recentDone, admin).get("archivedAt").isNull()).isTrue();
        perform(get("/api/tasks/" + longArchived), admin).andExpect(status().isNotFound());
        perform(get("/api/tasks/" + subtask), admin).andExpect(status().isNotFound());
        assertThat(texts(getJson("/api/tasks/" + open + "/comments", admin), "body")).containsExactly("keep me");
    }

    // ---- Encryption -----------------------------------------------------------------------------------------------

    @Test
    void attachmentsAreEncryptedAtRest() throws Exception {
        Account admin = admin();
        String key = project(admin);
        long id = task(admin, key, "Contract").get("id").asLong();
        byte[] secret = "Top secret contract terms".getBytes(StandardCharsets.UTF_8);
        long attachment = read(perform(multipart("/api/tasks/" + id + "/attachments")
                .file(new MockMultipartFile("file", "contract.txt", "text/plain", secret)), admin).andExpect(status().isCreated()))
                .get("id").asLong();

        // On disk only ciphertext…
        List<Path> files;
        try (var stream = Files.list(STORAGE)) {
            files = stream.toList();
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            byte[] raw = Files.readAllBytes(file);
            assertThat(new String(raw, StandardCharsets.ISO_8859_1)).startsWith("FJENC1").doesNotContain("secret contract");
        }
        // …but people get the original.
        byte[] downloaded = perform(get("/api/attachments/" + attachment + "/content"), admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(secret);

        // A file stored before encryption was switched on is encrypted by the admin action.
        Files.writeString(STORAGE.resolve("legacyfile"), "old plain file");
        assertThat(getJson("/api/admin/encryption", admin).get("plain").asInt()).isEqualTo(1);
        JsonNode result = read(perform(post("/api/admin/encryption/encrypt-all"), admin).andExpect(status().isOk()));
        assertThat(result.get("changed").asInt()).isEqualTo(1);
        assertThat(result.get("status").get("plain").asInt()).isZero();
        assertThat(Files.readString(STORAGE.resolve("legacyfile"), StandardCharsets.ISO_8859_1)).startsWith("FJENC1");
    }

    @Test
    void oldKeysStillDecryptAfterRotation(@TempDir Path dir) throws Exception {
        String oldKey = key();
        String newKey = key();
        AttachmentStorage before = new AttachmentStorage(dir.toString(), oldKey, "");
        String name = before.store(new MockMultipartFile("file", "a.txt", "text/plain", "rotate me".getBytes()));
        AttachmentStorage after = new AttachmentStorage(dir.toString(), newKey, oldKey);
        assertThat(after.load(name).getContentAsByteArray()).isEqualTo("rotate me".getBytes());
        assertThat(after.status().oldKey()).isEqualTo(1);
        assertThat(after.encryptAll()).isEqualTo(1);
        AttachmentStorage onlyNew = new AttachmentStorage(dir.toString(), newKey, "");
        assertThat(onlyNew.load(name).getContentAsByteArray()).isEqualTo("rotate me".getBytes());
        AttachmentStorage wrong = new AttachmentStorage(dir.toString(), oldKey, "");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> wrong.load(name)).hasMessageContaining("key");
    }

    // ---- Restore --------------------------------------------------------------------------------------------------

    @Test
    void restoreChecksPasswordAndDatabaseKind() throws Exception {
        Account admin = admin();
        JsonNode status = getJson("/api/admin/restore", admin);
        // The test database is in memory, which cannot be restored this way.
        assertThat(status.get("supported").asBoolean()).isFalse();
        String name = read(perform(post("/api/admin/backups"), admin).andExpect(status().isCreated())).get("name").asText();
        perform(post("/api/admin/backups/" + name + "/restore"), admin, j(Map.of("password", "wrong")))
                .andExpect(status().isBadRequest());
        perform(post("/api/admin/backups/" + name + "/restore"), admin, j(Map.of("password", PASSWORD)))
                .andExpect(status().isBadRequest());
        perform(post("/api/admin/backups/" + name + "/restore"), register(), j(Map.of("password", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void restoreOnStartupSwapsInTheBackup(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("data"));
        Path backups = Files.createDirectories(data.resolve("backups"));
        Path attachments = Files.createDirectories(data.resolve("attachments"));
        Files.writeString(data.resolve("fakejira.mv.db"), "CURRENT DATABASE");
        Files.writeString(attachments.resolve("current-file"), "current attachment");

        ByteArrayOutputStream innerBytes = new ByteArrayOutputStream();
        try (ZipOutputStream inner = new ZipOutputStream(innerBytes)) {
            inner.putNextEntry(new ZipEntry("fakejira.mv.db"));
            inner.write("RESTORED DATABASE".getBytes());
            inner.closeEntry();
        }
        Path backup = backups.resolve("fakejira-backup-20260101-030000.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(backup))) {
            zip.putNextEntry(new ZipEntry("database.zip"));
            zip.write(innerBytes.toByteArray());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("attachments/restored-file"));
            zip.write("restored attachment".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("attachments/../../escape"));
            zip.write("nope".getBytes());
            zip.closeEntry();
        }
        String url = "jdbc:h2:file:" + data.resolve("fakejira") + ";AUTO_SERVER=TRUE";

        // A zip-slip entry makes the whole restore fail, leaving the current data alone.
        Files.copy(backup, backups.resolve("restore-source.zip"));
        Files.writeString(backups.resolve("restore-pending.txt"), "restore-source.zip\nfakejira-backup-20260101-030000.zip\n");
        assertThat(RestoreOnStartup.apply(backups, url, attachments)).isFalse();
        assertThat(Files.readString(backups.resolve("restore-result.txt"))).startsWith("failed");
        assertThat(Files.exists(backups.resolve("restore-pending.txt"))).isFalse();
        assertThat(Files.exists(root.resolve("escape"))).isFalse();
        assertThat(Files.readString(data.resolve("fakejira.mv.db"))).isEqualTo("CURRENT DATABASE");
        assertThat(Files.readString(attachments.resolve("current-file"))).isEqualTo("current attachment");

        // A clean backup is applied; the old data is kept aside.
        Path clean = backups.resolve("fakejira-backup-20260102-030000.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(clean))) {
            zip.putNextEntry(new ZipEntry("database.zip"));
            zip.write(innerBytes.toByteArray());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("attachments/restored-file"));
            zip.write("restored attachment".getBytes());
            zip.closeEntry();
        }
        Files.copy(clean, backups.resolve("restore-source.zip"));
        Files.writeString(backups.resolve("restore-pending.txt"), "restore-source.zip\nfakejira-backup-20260102-030000.zip\n");
        assertThat(RestoreOnStartup.apply(backups, url, attachments)).isTrue();
        assertThat(Files.readString(data.resolve("fakejira.mv.db"))).isEqualTo("RESTORED DATABASE");
        assertThat(Files.readString(attachments.resolve("restored-file"))).isEqualTo("restored attachment");
        assertThat(Files.exists(attachments.resolve("current-file"))).isFalse();
        try (var kept = Files.list(data)) {
            assertThat(kept.map(p -> p.getFileName().toString()).toList())
                    .anyMatch(n -> n.startsWith("fakejira.mv.db.before-restore-"))
                    .anyMatch(n -> n.startsWith("attachments.before-restore-"));
        }
        assertThat(Files.readString(backups.resolve("restore-result.txt"))).startsWith("ok");
        assertThat(Files.exists(backups.resolve("restore-source.zip"))).isFalse();
        // Nothing pending: nothing happens.
        assertThat(RestoreOnStartup.apply(backups, url, attachments)).isFalse();
    }

    // ---- Health ---------------------------------------------------------------------------------------------------

    @Test
    void healthChecksAlertAdminsOnChange() throws Exception {
        Account admin = admin();
        JsonNode health = getJson("/api/admin/health", admin);
        assertThat(texts(health.get("checks"), "id")).contains("disk", "backup", "errors", "memory");
        // An impossible disk threshold makes the disk check fail and alerts the admin once.
        JsonNode failing = read(perform(put("/api/admin/health"), admin, j(Map.of("minFreeDiskMb", Integer.MAX_VALUE,
                "maxBackupAgeHours", 36, "maxServerErrors", 25, "maxHeapPercent", 95, "alerts", true))).andExpect(status().isOk()));
        JsonNode disk = null;
        for (JsonNode c : failing.get("checks")) if (c.get("id").asText().equals("disk")) disk = c;
        assertThat(disk.get("ok").asBoolean()).isFalse();
        perform(post("/api/admin/health/check"), admin).andExpect(status().isOk());
        String notes = getJson("/api/notifications", admin).toString();
        assertThat(notes.split("Needs attention: Disk space", -1).length - 1).isEqualTo(1);
        // Back to normal: one "resolved" message.
        perform(put("/api/admin/health"), admin, j(Map.of("minFreeDiskMb", 0, "maxBackupAgeHours", 36, "maxServerErrors", 25,
                "maxHeapPercent", 95, "alerts", true))).andExpect(status().isOk());
        assertThat(getJson("/api/notifications", admin).toString()).contains("Resolved: Disk space");
        perform(put("/api/admin/health"), admin, j(Map.of("minFreeDiskMb", 0, "maxBackupAgeHours", 0, "maxServerErrors", 25,
                "maxHeapPercent", 95, "alerts", true))).andExpect(status().isBadRequest());
    }
}
