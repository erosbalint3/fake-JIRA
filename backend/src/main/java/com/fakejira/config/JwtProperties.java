package com.fakejira.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param secret   HMAC secret used to sign tokens (at least 32 characters). When blank, a random
 *                 secret is generated on startup, which invalidates all tokens on restart.
 * @param validity how long an issued token stays valid
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Duration validity) {

    public JwtProperties {
        if (validity == null) {
            validity = Duration.ofHours(12);
        }
    }
}
