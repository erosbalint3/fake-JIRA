package com.fakejira.config;

import com.fakejira.apitoken.ApiTokenService;
import com.fakejira.auth.TokenService;
import com.fakejira.session.SessionService;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import java.time.Instant;

/**
 * Accepts sign-in tokens only while their session is active. Rejects special-purpose tokens (2FA step)
 * and tokens from before sessions existed, which simply means signing in again once after the upgrade.
 */
public class AppJwtDecoder implements JwtDecoder {

    /** Claims on the synthetic JWT of a request made with an API token. */
    public static final String API_TOKEN = "api_token";
    public static final String API_SCOPE = "api_scope";

    private final NimbusJwtDecoder jwt;
    private final SessionService sessions;
    private final ApiTokenService apiTokens;

    public AppJwtDecoder(SecretKey key, SessionService sessions, ApiTokenService apiTokens) {
        this.jwt = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.sessions = sessions;
        this.apiTokens = apiTokens;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        if (token.startsWith(ApiTokenService.PREFIX)) {
            ApiTokenService.Identity identity = apiTokens.authenticate(token)
                    .orElseThrow(() -> new BadJwtException("Invalid or expired API token"));
            Instant now = Instant.now();
            return Jwt.withTokenValue(token).header("alg", "none")
                    .subject(String.valueOf(identity.userId()))
                    .claim(API_TOKEN, identity.tokenId())
                    .claim(API_SCOPE, identity.scope().name())
                    .issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        }
        Jwt decoded = jwt.decode(token);
        if (decoded.getClaimAsString(TokenService.PURPOSE) != null) {
            throw new BadJwtException("Not a sign-in token");
        }
        String sessionId = decoded.getClaimAsString(TokenService.SESSION);
        long userId;
        try {
            userId = Long.parseLong(decoded.getSubject());
        } catch (NumberFormatException e) {
            throw new BadJwtException("Invalid subject");
        }
        if (sessionId == null || !sessions.isActive(sessionId, userId)) {
            throw new BadJwtException("This session has ended. Please sign in again.");
        }
        return decoded;
    }
}
