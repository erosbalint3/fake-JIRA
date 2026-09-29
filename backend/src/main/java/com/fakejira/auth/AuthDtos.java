package com.fakejira.auth;

import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "Username is required")
            @Size(min = 4, max = 40, message = "Username must be 4-40 characters long")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "Only letters, numbers, dots, dashes and underscores")
            String username,

            @NotBlank(message = "Email is required")
            @Email(message = "Enter a valid email address")
            @Size(max = 120)
            String email,

            @NotBlank(message = "Password is required")
            @PolicyPassword
            String password,

            /** Required when sign-up is invite-only. */
            String inviteCode) {
    }

    public record LoginRequest(
            @NotBlank(message = "Enter your username or email") String login,
            @NotBlank(message = "Enter your password") String password) {
    }

    /** {@code currentPassword} may be empty for accounts that never had one (Google/GitHub sign-in). */
    public record ChangePasswordRequest(
            String currentPassword,

            @NotBlank(message = "Password is required")
            @PolicyPassword
            String newPassword) {
    }

    public record ForgotPasswordRequest(
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email address") String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "The reset link is incomplete") String token,

            @NotBlank(message = "Password is required")
            @PolicyPassword
            String newPassword) {
    }

    /**
     * {@code token} is null and {@code pending} true when the account awaits admin approval. When two-factor
     * authentication is on, {@code token} is null and {@code challenge} must be sent with a code to
     * /api/auth/login/2fa.
     */
    public record AuthResponse(String token, UserSummary user, boolean pending, boolean admin, String challenge) {

        public static AuthResponse signedIn(String token, User user) {
            return new AuthResponse(token, UserSummary.of(user), false, user.isAdmin(), null);
        }

        public static AuthResponse pending(User user) {
            return new AuthResponse(null, UserSummary.of(user), true, false, null);
        }

        public static AuthResponse secondStep(String challenge) {
            return new AuthResponse(null, null, false, false, challenge);
        }
    }

    public record TwoFactorLoginRequest(
            @NotBlank(message = "The sign-in step expired, start again") String challenge,
            @NotBlank(message = "Enter the code from your authenticator app") String code) {
    }

    public record PasswordRules(int minLength, boolean upper, boolean digit, boolean special) {
    }

    public record InviteInfo(boolean valid, String email, String projectName, String registrationMode) {
    }
}
