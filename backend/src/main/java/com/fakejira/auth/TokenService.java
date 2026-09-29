package com.fakejira.auth;

import com.fakejira.config.JwtProperties;
import com.fakejira.user.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TokenService {

    /** Claim marking a token that is not a sign-in token (e.g. the 2FA step); the API rejects these. */
    public static final String PURPOSE = "purpose";
    public static final String SESSION = "sid";
    private static final Duration CHALLENGE_VALIDITY = Duration.ofMinutes(5);

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final NimbusJwtDecoder decoder;

    public TokenService(JwtEncoder encoder, JwtProperties properties, SecretKey jwtSecretKey) {
        this.encoder = encoder;
        this.properties = properties;
        this.decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /** A sign-in token for one session. */
    public String issue(User user, String sessionId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("fake-jira")
                .issuedAt(now)
                .expiresAt(now.plus(properties.validity()))
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim(SESSION, sessionId)
                .build();
        return encode(claims);
    }

    /** A short-lived token proving the first sign-in step (password or Google/GitHub) for {@code purpose}. */
    public String challenge(User user, String purpose, String method) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("fake-jira")
                .issuedAt(now)
                .expiresAt(now.plus(CHALLENGE_VALIDITY))
                .subject(String.valueOf(user.getId()))
                .id(UUID.randomUUID().toString())
                .claim(PURPOSE, purpose)
                .claim("method", method)
                .build();
        return encode(claims);
    }

    /** Reads a challenge token for {@code purpose}; empty when invalid or expired. */
    public Optional<Jwt> readChallenge(String token, String purpose) {
        try {
            Jwt jwt = decoder.decode(token);
            return purpose.equals(jwt.getClaimAsString(PURPOSE)) ? Optional.of(jwt) : Optional.empty();
        } catch (JwtException e) {
            return Optional.empty();
        }
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
