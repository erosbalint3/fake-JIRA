package com.fakejira.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import com.fakejira.session.SessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless bearer-token API: no cookies are used for auth, so CSRF does not apply.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Async dispatches carry on an already-authorized request (SSE streams).
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/login/2fa", "/api/auth/register",
                                "/api/auth/forgot-password", "/api/auth/reset-password", "/api/auth/oauth/*/url",
                                "/api/auth/oauth/saml/acs").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/invite", "/api/auth/password-policy",
                                "/api/auth/providers", "/api/auth/oauth/*/callback", "/api/auth/saml/metadata", "/api/avatars/**",
                                "/api/push/key").permitAll()
                        // Authenticated by HMAC signature instead of a JWT.
                        .requestMatchers(HttpMethod.POST, "/api/integrations/github/**", "/api/integrations/gitlab/**",
                                "/api/integrations/gitea/**", "/api/integrations/ci/**", "/api/integrations/slack/**",
                                "/api/integrations/mattermost/**", "/api/integrations/discord/**").permitAll()
                        // The Google Calendar OAuth callback carries its own signed state.
                        .requestMatchers(HttpMethod.GET, "/api/integrations/google-calendar/callback").permitAll()
                        // Secret-token URLs: the personal calendar feed and the inbound email webhook.
                        .requestMatchers(HttpMethod.GET, "/api/calendar/feed/*", "/api/public/**", "/api/metrics", "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/inbound/email").permitAll()
                        // The public service desk portal (and its embeddable widget).
                        .requestMatchers(HttpMethod.POST, "/api/public/portal/*/requests", "/api/public/requests/*/messages").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                // Pages may only be framed by this site, except the feedback widget, which other sites embed.
                .headers(headers -> headers.frameOptions(frame -> frame.disable())
                        .addHeaderWriter(new org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter(
                                request -> !request.getRequestURI().startsWith("/embed/"),
                                new org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter(
                                        org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode.SAMEORIGIN))))
                .build();
    }

    /** /api/metrics uses its own token (APP_METRICS_TOKEN), so it is not read as a sign-in token. */
    @Bean
    org.springframework.security.oauth2.server.resource.web.BearerTokenResolver bearerTokenResolver() {
        var standard = new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver();
        // The metrics endpoint and SCIM check their own bearer tokens.
        return request -> "/api/metrics".equals(request.getRequestURI()) || request.getRequestURI().startsWith("/scim/")
                ? null : standard.resolve(request);
    }

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        byte[] secret;
        if (properties.secret() == null || properties.secret().isBlank()) {
            log.warn("app.jwt.secret is not set; generating a random secret. Tokens will not survive a restart.");
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
        } else {
            secret = properties.secret().getBytes(StandardCharsets.UTF_8);
            if (secret.length < 32) {
                throw new IllegalStateException("app.jwt.secret must be at least 32 bytes long");
            }
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, SessionService sessions, com.fakejira.apitoken.ApiTokenService apiTokens) {
        return new AppJwtDecoder(jwtSecretKey, sessions, apiTokens);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
