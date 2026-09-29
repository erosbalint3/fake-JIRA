package com.fakejira.common;

import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link User} behind the JWT of the current request.
 */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public User from(Jwt jwt) {
        User user = users.findById(Long.valueOf(jwt.getSubject()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Your session is no longer valid."));
        if (user.getStatus() != com.fakejira.user.AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Your account is not active.");
        }
        if (user.isMustChangePassword() && blockedUntilPasswordChange()) {
            throw ApiException.forbidden("Choose a new password before making changes.");
        }
        if (jwt.hasClaim(com.fakejira.config.AppJwtDecoder.API_TOKEN)) {
            checkApiToken(jwt);
        }
        return user;
    }

    /** While a new password is required, only reading and the profile/sign-in endpoints work. */
    private static boolean blockedUntilPasswordChange() {
        if (!(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes)) {
            return false;
        }
        var request = attributes.getRequest();
        String path = request.getRequestURI();
        return !"GET".equals(request.getMethod()) && !path.startsWith("/api/auth/") && !path.startsWith("/api/profile");
    }

    /**
     * API tokens reach projects and tasks only: not account, security or admin settings. Read tokens only read.
     */
    private static void checkApiToken(Jwt jwt) {
        if (!(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes)) {
            return;
        }
        var request = attributes.getRequest();
        String path = request.getRequestURI();
        boolean reading = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        if (path.startsWith("/api/admin") || path.startsWith("/api/profile") || path.startsWith("/api/invites")
                || (path.startsWith("/api/auth/") && !path.equals("/api/auth/me"))) {
            throw ApiException.forbidden("API tokens cannot be used for account or admin settings. Sign in instead.");
        }
        if (!reading && "READ".equals(jwt.getClaimAsString(com.fakejira.config.AppJwtDecoder.API_SCOPE))) {
            throw ApiException.forbidden("This API token is read-only.");
        }
    }

    public User admin(Jwt jwt) {
        User user = from(jwt);
        if (!user.isAdmin()) {
            throw ApiException.forbidden("Only admins can do that.");
        }
        return user;
    }
}
