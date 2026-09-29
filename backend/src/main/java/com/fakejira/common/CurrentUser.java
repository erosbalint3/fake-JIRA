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
        return user;
    }

    public User admin(Jwt jwt) {
        User user = from(jwt);
        if (!user.isAdmin()) {
            throw ApiException.forbidden("Only admins can do that.");
        }
        return user;
    }
}
