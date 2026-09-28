package com.fakejira.auth;

import com.fakejira.auth.AuthDtos.AuthResponse;
import com.fakejira.auth.AuthDtos.LoginRequest;
import com.fakejira.auth.AuthDtos.RegisterRequest;
import com.fakejira.common.ApiException;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fakejira.user.UserSummary;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();
        if (users.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("That username is already taken.");
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with that email already exists.");
        }
        User user = users.save(new User(username, email, passwordEncoder.encode(request.password())));
        return new AuthResponse(tokens.issue(user), UserSummary.of(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String login = request.login().trim();
        User user = (login.contains("@")
                ? users.findByEmailIgnoreCase(login)
                : users.findByUsernameIgnoreCase(login))
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username/email or password."));
        return new AuthResponse(tokens.issue(user), UserSummary.of(user));
    }
}
