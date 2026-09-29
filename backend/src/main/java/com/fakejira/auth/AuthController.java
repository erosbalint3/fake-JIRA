package com.fakejira.auth;

import com.fakejira.auth.AuthDtos.AuthResponse;
import com.fakejira.auth.AuthDtos.ForgotPasswordRequest;
import com.fakejira.auth.AuthDtos.InviteInfo;
import com.fakejira.auth.AuthDtos.LoginRequest;
import com.fakejira.auth.AuthDtos.RegisterRequest;
import com.fakejira.auth.AuthDtos.PasswordRules;
import com.fakejira.auth.AuthDtos.ResetPasswordRequest;
import com.fakejira.auth.AuthDtos.TwoFactorLoginRequest;
import com.fakejira.session.SessionService;
import com.fakejira.common.CurrentUser;
import com.fakejira.mail.MailService;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordReset;
    private final CurrentUser currentUser;
    private final MailService mail;
    private final SessionService sessions;
    private final PasswordPolicy policy;

    public AuthController(AuthService authService, PasswordResetService passwordReset, CurrentUser currentUser,
                          MailService mail, SessionService sessions, PasswordPolicy policy) {
        this.sessions = sessions;
        this.policy = policy;
        this.authService = authService;
        this.passwordReset = passwordReset;
        this.currentUser = currentUser;
        this.mail = mail;
    }

    /** {@code mustChangePassword}: an admin requires a new password before anything else. */
    public record MeResponse(UserSummary user, boolean admin, boolean mustChangePassword, String language) {
    }

    /** 201 with a token, or 202 without one when the account needs admin approval. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(response.pending() ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** Second sign-in step when two-factor authentication is on. */
    @PostMapping("/login/2fa")
    public AuthResponse loginSecondStep(@Valid @RequestBody TwoFactorLoginRequest request) {
        return authService.loginSecondStep(request.challenge(), request.code());
    }

    /** Signs this device out. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt) {
        sessions.revoke(Long.valueOf(jwt.getSubject()), jwt.getClaimAsString(TokenService.SESSION));
    }

    @GetMapping("/password-policy")
    public PasswordRules passwordPolicy() {
        PasswordPolicy.Rules rules = policy.rules();
        return new PasswordRules(rules.minLength(), rules.upper(), rules.digit(), rules.special());
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordReset.requestReset(request.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordReset.reset(request.token(), request.newPassword());
    }

    /** Public: tells the sign-up page the registration mode and prefills invite details. */
    @GetMapping("/invite")
    public InviteInfo invite(@RequestParam(defaultValue = "") String code) {
        return authService.inviteInfo(code);
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        User user = currentUser.from(jwt);
        if (user.isAdmin()) {
            // Without APP_BASE_URL, links in emails and invites use the address admins open the app at.
            mail.rememberSiteUrl(request);
        }
        return new MeResponse(UserSummary.of(user), user.isAdmin(), user.isMustChangePassword(), user.getLanguage());
    }
}
