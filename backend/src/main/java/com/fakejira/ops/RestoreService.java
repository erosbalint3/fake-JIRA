package com.fakejira.ops;

import com.fakejira.admin.BackupService;
import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * One-click restore of the built-in database and files from a backup. The admin confirms with their password;
 * a fresh backup of the current state is taken first, then the app restarts and {@link RestoreOnStartup} swaps
 * the data in before the database opens. Docker's restart policy (or systemd) brings the app back up.
 */
@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);

    /** {@code lastResult}: "ok" or "failed" with the backup name and time, from the last restore. */
    public record Status(boolean supported, String reason, List<String> lastResult) {
    }

    private final BackupService backups;
    private final PasswordEncoder passwords;
    private final AuditLog audit;
    private final ConfigurableApplicationContext context;
    private final Path backupDir;
    private final String datasourceUrl;
    private final boolean restartAfterScheduling;

    public RestoreService(BackupService backups, PasswordEncoder passwords, AuditLog audit, ConfigurableApplicationContext context,
                          @Value("${app.backup.dir:./data/backups}") String backupDir,
                          @Value("${spring.datasource.url}") String datasourceUrl,
                          @Value("${app.restore.restart:true}") boolean restartAfterScheduling) {
        this.backups = backups;
        this.passwords = passwords;
        this.audit = audit;
        this.context = context;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.datasourceUrl = datasourceUrl;
        this.restartAfterScheduling = restartAfterScheduling;
    }

    public Status status() {
        boolean supported = RestoreOnStartup.databaseFile(datasourceUrl) != null;
        List<String> last = List.of();
        try {
            Path result = backupDir.resolve(RestoreOnStartup.RESULT);
            if (Files.isRegularFile(result)) {
                last = Files.readAllLines(result, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
            }
        } catch (IOException ignored) {
            // no result to show
        }
        return new Status(supported, supported ? null
                : "One-click restore works with the built-in database. For PostgreSQL, follow the steps in the backup's README.", last);
    }

    /** Checks the password and the backup, backs up the current state, then restarts into the restore. */
    public synchronized String schedule(User admin, String name, String password) {
        if (password == null || !admin.isPasswordSet() || !passwords.matches(password, admin.getPasswordHash())) {
            throw ApiException.field("password", "That password is not right.");
        }
        if (!status().supported()) {
            throw ApiException.badRequest(status().reason());
        }
        Path backup = backups.file(name);
        try (ZipFile zip = new ZipFile(backup.toFile())) {
            if (zip.getEntry("database.zip") == null) {
                throw ApiException.badRequest("That backup has no database copy.");
            }
        } catch (IOException e) {
            throw ApiException.badRequest("That backup file is damaged.");
        }
        try {
            // A private copy, so rotating old backups (the safety backup below) cannot remove it.
            Files.copy(backup, backupDir.resolve(RestoreOnStartup.SOURCE), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare the restore.");
        }
        BackupService.BackupFile safety = backups.create();
        try {
            Files.writeString(backupDir.resolve(RestoreOnStartup.MARKER), RestoreOnStartup.SOURCE + "\n" + name + "\n");
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not schedule the restore.");
        }
        audit.record(admin, "admin.restore", name, "safety backup " + safety.name());
        if (restartAfterScheduling) {
            Thread restart = new Thread(() -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Restarting to restore backup {}", name);
                System.exit(SpringApplication.exit(context, () -> 3));
            }, "restore-restart");
            restart.setDaemon(false);
            restart.start();
        }
        return safety.name();
    }
}
