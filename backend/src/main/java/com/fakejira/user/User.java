package com.fakejira.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String username;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // Columns added after v2.0 carry explicit defaults so they can be added to existing tables.

    /** Legacy (v2.1) on/off switch; migrated into {@link #emailFrequency} on startup. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean emailNotifications;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, columnDefinition = "varchar(10) default 'OFF'")
    private EmailFrequency emailFrequency = EmailFrequency.OFF;

    /** When the last digest email was sent (daily/weekly frequencies). */
    private Instant lastDigestAt;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean admin;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 10, columnDefinition = "varchar(10) default 'ACTIVE'")
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(length = 60)
    private String displayName;

    /** Random file name of the uploaded profile picture, if any. */
    @Column(length = 64)
    private String avatarName;

    // ---- two-factor authentication (TOTP, RFC 6238)
    @Column(length = 64)
    private String totpSecret;

    /** Secret shown during setup, until the first code confirms it. */
    @Column(length = 64)
    private String totpPendingSecret;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean totpEnabled;

    /** Last 30-second step a code was accepted for; a code cannot be used twice. */
    private Long totpLastStep;

    /** BCrypt hashes of unused recovery codes, separated by spaces. */
    @Column(length = 1200)
    private String recoveryCodes;

    // ---- password policy
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean mustChangePassword;

    /** False for accounts created through Google/GitHub sign-in until they set a password. */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean passwordSet = true;

    private Instant deletedAt;

    protected User() {
    }

    public User(String username, String email, String passwordHash) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    /** Display name if set, otherwise the username. */
    public String getName() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isEmailNotifications() {
        return emailNotifications;
    }

    public void setEmailNotifications(boolean emailNotifications) {
        this.emailNotifications = emailNotifications;
    }

    public EmailFrequency getEmailFrequency() {
        return emailFrequency;
    }

    public void setEmailFrequency(EmailFrequency emailFrequency) {
        this.emailFrequency = emailFrequency;
    }

    public Instant getLastDigestAt() {
        return lastDigestAt;
    }

    public void setLastDigestAt(Instant lastDigestAt) {
        this.lastDigestAt = lastDigestAt;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarName() {
        return avatarName;
    }

    public void setAvatarName(String avatarName) {
        this.avatarName = avatarName;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public String getTotpPendingSecret() {
        return totpPendingSecret;
    }

    public void setTotpPendingSecret(String totpPendingSecret) {
        this.totpPendingSecret = totpPendingSecret;
    }

    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    public void enableTotp(String secret, String recoveryCodeHashes) {
        this.totpSecret = secret;
        this.totpPendingSecret = null;
        this.totpEnabled = true;
        this.totpLastStep = null;
        this.recoveryCodes = recoveryCodeHashes;
    }

    public void disableTotp() {
        this.totpSecret = null;
        this.totpPendingSecret = null;
        this.totpEnabled = false;
        this.totpLastStep = null;
        this.recoveryCodes = null;
    }

    public Long getTotpLastStep() {
        return totpLastStep;
    }

    public void setTotpLastStep(Long totpLastStep) {
        this.totpLastStep = totpLastStep;
    }

    public String getRecoveryCodes() {
        return recoveryCodes;
    }

    public void setRecoveryCodes(String recoveryCodes) {
        this.recoveryCodes = recoveryCodes;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public boolean isPasswordSet() {
        return passwordSet;
    }

    public void setPasswordSet(boolean passwordSet) {
        this.passwordSet = passwordSet;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /** Erases personal data but keeps the row, so comments and history still have an author. */
    public void anonymize(String passwordHash) {
        this.username = "deleted-" + id;
        this.email = "deleted-" + id + "@deleted.invalid";
        this.displayName = "Deleted user";
        this.passwordHash = passwordHash;
        this.avatarName = null;
        this.admin = false;
        this.status = AccountStatus.DELETED;
        this.emailFrequency = EmailFrequency.OFF;
        this.emailNotifications = false;
        this.deletedAt = Instant.now();
        disableTotp();
    }
}
