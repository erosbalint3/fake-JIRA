package com.fakejira.auth;

import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Authenticator-app codes as a second sign-in step, with single-use recovery codes. */
@Service
@Transactional
public class TwoFactorService {

    private static final int RECOVERY_CODES = 10;
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    public record Setup(String secret, String otpauthUrl) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    public TwoFactorService(UserRepository users, PasswordEncoder passwords, AuditLog audit) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
    }

    /** Starts setup: a new secret for the authenticator app, active only after {@link #enable}. */
    public Setup setup(User user) {
        User managed = users.findById(user.getId()).orElseThrow();
        if (managed.isTotpEnabled()) {
            throw ApiException.badRequest("Two-factor authentication is already on.");
        }
        String secret = Totp.newSecret();
        managed.setTotpPendingSecret(secret);
        return new Setup(secret, Totp.otpauthUrl(secret, managed.getUsername()));
    }

    /** Confirms setup with a first code. Returns the recovery codes, shown to the user once. */
    public List<String> enable(User user, String code) {
        User managed = users.findById(user.getId()).orElseThrow();
        String secret = managed.getTotpPendingSecret();
        if (secret == null) {
            throw ApiException.badRequest("Start the setup first.");
        }
        long step = Totp.verify(secret, code, null, Totp.currentStep());
        if (step < 0) {
            throw ApiException.field("code", "That code is not right. Check the time on your phone and try again.");
        }
        List<String> codes = newRecoveryCodes();
        managed.enableTotp(secret, hash(codes));
        managed.setTotpLastStep(step);
        audit.record(managed, "2fa.enable", managed.getUsername(), null);
        return codes;
    }

    /** Turns 2FA off; needs the password (when the account has one) and a current code or recovery code. */
    public void disable(User user, String password, String code) {
        User managed = users.findById(user.getId()).orElseThrow();
        if (!managed.isTotpEnabled()) {
            return;
        }
        if (managed.isPasswordSet() && (password == null || !passwords.matches(password, managed.getPasswordHash()))) {
            throw ApiException.field("password", "Your password is not correct.");
        }
        if (!verify(managed, code)) {
            throw ApiException.field("code", "That code is not right.");
        }
        managed.disableTotp();
        audit.record(managed, "2fa.disable", managed.getUsername(), null);
    }

    public List<String> regenerateRecoveryCodes(User user, String code) {
        User managed = users.findById(user.getId()).orElseThrow();
        if (!managed.isTotpEnabled()) {
            throw ApiException.badRequest("Two-factor authentication is off.");
        }
        if (!verify(managed, code)) {
            throw ApiException.field("code", "That code is not right.");
        }
        List<String> codes = newRecoveryCodes();
        managed.setRecoveryCodes(hash(codes));
        audit.record(managed, "2fa.recovery_codes", managed.getUsername(), "regenerated");
        return codes;
    }

    public int remainingRecoveryCodes(User user) {
        String codes = user.getRecoveryCodes();
        return codes == null || codes.isBlank() ? 0 : codes.trim().split(" ").length;
    }

    /** Admin help for a lost phone: turns 2FA off so the user can sign in with the password alone. */
    public void reset(User admin, User target) {
        User managed = users.findById(target.getId()).orElseThrow();
        managed.disableTotp();
        audit.record(admin, "2fa.reset", managed.getUsername(), "by admin");
    }

    /** Checks an authenticator code (not reusable) or a recovery code (used up). */
    public boolean verify(User managed, String code) {
        if (!managed.isTotpEnabled() || code == null) {
            return false;
        }
        String trimmed = code.trim().toLowerCase(Locale.ROOT);
        long step = Totp.verify(managed.getTotpSecret(), trimmed, managed.getTotpLastStep(), Totp.currentStep());
        if (step >= 0) {
            managed.setTotpLastStep(step);
            return true;
        }
        if (!trimmed.matches("[a-z0-9]{4}-?[a-z0-9]{4}") || managed.getRecoveryCodes() == null) {
            return false;
        }
        String normalized = trimmed.replace("-", "");
        List<String> hashes = new ArrayList<>(Arrays.asList(managed.getRecoveryCodes().trim().split(" ")));
        for (String hash : hashes) {
            if (!hash.isEmpty() && passwords.matches(normalized, hash)) {
                hashes.remove(hash);
                managed.setRecoveryCodes(String.join(" ", hashes));
                audit.record(managed, "2fa.recovery_code_used", managed.getUsername(), hashes.size() + " left");
                return true;
            }
        }
        return false;
    }

    private List<String> newRecoveryCodes() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder code = new StringBuilder();
            for (int j = 0; j < 8; j++) {
                code.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
                if (j == 3) {
                    code.append('-');
                }
            }
            codes.add(code.toString());
        }
        return codes;
    }

    private String hash(List<String> codes) {
        return String.join(" ", codes.stream().map(c -> passwords.encode(c.replace("-", ""))).toList());
    }
}
