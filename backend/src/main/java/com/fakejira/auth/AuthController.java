package com.fakejira.auth;

import com.fakejira.auth.AuthDtos.AuthResponse;
import com.fakejira.auth.AuthDtos.ForgotPasswordRequest;
import com.fakejira.auth.AuthDtos.InviteInfo;
import com.fakejira.auth.AuthDtos.LoginRequest;
import com.fakejira.auth.AuthDtos.RegisterRequest;
import com.fakejira.auth.AuthDtos.ResetPasswordRequest;
import com.fakejira.common.CurrentUser;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
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

    public AuthController(AuthService authService, PasswordResetService passwordReset, CurrentUser currentUser) {
        this.authService = authService;
        this.passwordReset = passwordReset;
        this.currentUser = currentUser;
    }

    public record MeResponse(UserSummary user, boolean admin) {
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
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return new MeResponse(UserSummary.of(user), user.isAdmin());
    }
}
