package com.fakejira.auth;

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
            @Size(min = 8, max = 100, message = "Password must be at least 8 characters long")
            @Pattern(regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).*$",
                    message = "Password needs an uppercase letter, a number and a special character")
            String password,

            /** Required when sign-up is invite-only. */
            String inviteCode) {
    }

    public record LoginRequest(
            @NotBlank(message = "Enter your username or email") String login,
            @NotBlank(message = "Enter your password") String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Enter your current password") String currentPassword,

            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 100, message = "Password must be at least 8 characters long")
            @Pattern(regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).*$",
                    message = "Password needs an uppercase letter, a number and a special character")
            String newPassword) {
    }

    public record ForgotPasswordRequest(
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email address") String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "The reset link is incomplete") String token,

            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 100, message = "Password must be at least 8 characters long")
            @Pattern(regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).*$",
                    message = "Password needs an uppercase letter, a number and a special character")
            String newPassword) {
    }

    /** {@code token} is null and {@code pending} true when the account awaits admin approval. */
    public record AuthResponse(String token, UserSummary user, boolean pending, boolean admin) {
    }

    public record InviteInfo(boolean valid, String email, String projectName, String registrationMode) {
    }
}
