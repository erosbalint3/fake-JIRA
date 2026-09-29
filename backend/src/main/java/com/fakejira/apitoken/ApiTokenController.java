package com.fakejira.apitoken;

import com.fakejira.audit.AuditLog;
import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Manage your API tokens (only with a normal sign-in; tokens cannot create tokens). */
@RestController
public class ApiTokenController {

    static final int MAX_TOKENS = 20;

    private final ApiTokenRepository tokens;
    private final ApiTokenService service;
    private final CurrentUser currentUser;
    private final AuditLog audit;

    public ApiTokenController(ApiTokenRepository tokens, ApiTokenService service, CurrentUser currentUser, AuditLog audit) {
        this.tokens = tokens;
        this.service = service;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    public record TokenRequest(
            @NotBlank(message = "Give the token a name") @Size(max = 60, message = "Name must be at most 60 characters") String name,
            @NotNull(message = "Pick read or write access") ApiToken.Scope scope,
            @Min(value = 1, message = "At least 1 day") @Max(value = 365, message = "At most 365 days") Integer expiresInDays) {
    }

    /** {@code token} (the secret) is only present in the response to creating it. */
    public record TokenResponse(Long id, String name, String prefix, ApiToken.Scope scope, Instant createdAt,
                                Instant lastUsedAt, Instant expiresAt, boolean expired, String token) {
    }

    @GetMapping("/api/profile/tokens")
    @Transactional(readOnly = true)
    public List<TokenResponse> list(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return tokens.findByUserIdOrderByIdDesc(user.getId()).stream().map(t -> response(t, null)).toList();
    }

    @PostMapping("/api/profile/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TokenResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TokenRequest request) {
        User user = currentUser.from(jwt);
        if (tokens.findByUserIdOrderByIdDesc(user.getId()).size() >= MAX_TOKENS) {
            throw ApiException.badRequest("You can have at most " + MAX_TOKENS + " tokens. Revoke one first.");
        }
        ApiTokenService.Created created = service.create(user, request.name().trim(), request.scope(), request.expiresInDays());
        audit.record(user, "token.create", created.token().getPrefix(), request.name().trim() + " (" + request.scope() + ")");
        return response(created.token(), created.secret());
    }

    @DeleteMapping("/api/profile/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        ApiToken token = tokens.findById(id).filter(t -> t.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> ApiException.notFound("Token not found."));
        audit.record(user, "token.revoke", token.getPrefix(), token.getName());
        tokens.delete(token);
    }

    private static TokenResponse response(ApiToken t, String secret) {
        return new TokenResponse(t.getId(), t.getName(), t.getPrefix(), t.getScope(), t.getCreatedAt(), t.getLastUsedAt(),
                t.getExpiresAt(), t.isExpired(), secret);
    }
}
