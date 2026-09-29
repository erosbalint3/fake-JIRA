package com.fakejira.config;

import com.fakejira.auth.TokenService;
import com.fakejira.session.SessionService;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;

/**
 * Accepts sign-in tokens only while their session is active. Rejects special-purpose tokens (2FA step)
 * and tokens from before sessions existed, which simply means signing in again once after the upgrade.
 */
public class AppJwtDecoder implements JwtDecoder {

    private final NimbusJwtDecoder jwt;
    private final SessionService sessions;

    public AppJwtDecoder(SecretKey key, SessionService sessions) {
        this.jwt = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.sessions = sessions;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
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
