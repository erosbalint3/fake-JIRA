package com.fakejira.user;

import com.fakejira.auth.TokenService;
import com.fakejira.auth.TwoFactorService;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.session.SessionService;
import com.fakejira.session.UserSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Signed-in devices, two-factor authentication, data export and account deletion for the current user. */
@RestController
@RequestMapping("/api/profile")
public class AccountSecurityController {

    private final CurrentUser currentUser;
    private final SessionService sessions;
    private final TwoFactorService twoFactor;
    private final AccountService accounts;
    private final PasswordEncoder passwords;

    public AccountSecurityController(CurrentUser currentUser, SessionService sessions, TwoFactorService twoFactor,
                                     AccountService accounts, PasswordEncoder passwords) {
        this.currentUser = currentUser;
        this.sessions = sessions;
        this.twoFactor = twoFactor;
        this.accounts = accounts;
        this.passwords = passwords;
    }

    public record SessionResponse(String id, boolean current, Instant createdAt, Instant lastSeenAt, String ip,
                                  String device, String method) {
        static SessionResponse of(UserSession s, String currentId) {
            return new SessionResponse(s.getId(), s.getId().equals(currentId), s.getCreatedAt(), s.getLastSeenAt(),
                    s.getIp(), s.getUserAgent(), s.getMethod());
        }
    }

    public record CodeRequest(String code) {
    }

    public record ConfirmRequest(String password, String code) {
    }

    // ---------------------------------------------------------------- sessions

    @GetMapping("/sessions")
    public List<SessionResponse> sessions(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        String current = jwt.getClaimAsString(TokenService.SESSION);
        return sessions.active(user.getId()).stream().map(s -> SessionResponse.of(s, current)).toList();
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOutDevice(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        User user = currentUser.from(jwt);
        if (!sessions.revoke(user.getId(), id)) {
            throw ApiException.notFound("Session not found.");
        }
    }

    /** Signs out every other device. */
    @PostMapping("/sessions/sign-out-others")
    public Map<String, Integer> signOutOthers(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return Map.of("signedOut", sessions.revokeAll(user.getId(), jwt.getClaimAsString(TokenService.SESSION)));
    }

    // ---------------------------------------------------------------- two-factor authentication

    @PostMapping("/2fa/setup")
    public TwoFactorService.Setup setupTwoFactor(@AuthenticationPrincipal Jwt jwt) {
        return twoFactor.setup(currentUser.from(jwt));
    }

    @PostMapping("/2fa/enable")
    public Map<String, List<String>> enableTwoFactor(@AuthenticationPrincipal Jwt jwt, @RequestBody CodeRequest request) {
        return Map.of("recoveryCodes", twoFactor.enable(currentUser.from(jwt), request.code()));
    }

    @PostMapping("/2fa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableTwoFactor(@AuthenticationPrincipal Jwt jwt, @RequestBody ConfirmRequest request) {
        twoFactor.disable(currentUser.from(jwt), request.password(), request.code());
    }

    @PostMapping("/2fa/recovery-codes")
    public Map<String, List<String>> newRecoveryCodes(@AuthenticationPrincipal Jwt jwt, @RequestBody CodeRequest request) {
        return Map.of("recoveryCodes", twoFactor.regenerateRecoveryCodes(currentUser.from(jwt), request.code()));
    }

    // ---------------------------------------------------------------- your data

    /** Everything stored about you, as a JSON download. */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("fakejira-" + user.getUsername() + "-" + LocalDate.now() + ".json").build().toString())
                .body(accounts.export(user));
    }

    /** Deletes your account after confirming with your password (and a 2FA code when that is on). */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal Jwt jwt, @RequestBody ConfirmRequest request) {
        User user = currentUser.from(jwt);
        if (user.isPasswordSet() && (request.password() == null || !passwords.matches(request.password(), user.getPasswordHash()))) {
            throw ApiException.field("password", "Your password is not correct.");
        }
        if (user.isTotpEnabled() && !twoFactor.verify(user, request.code())) {
            throw ApiException.field("code", "That code is not right.");
        }
        accounts.delete(user, user);
    }
}
