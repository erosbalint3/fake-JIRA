package com.fakejira.config;

import com.fakejira.admin.SecurityPolicy;
import com.fakejira.common.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Enforces the admin's IP allowlist on the API. Machine-to-machine endpoints that are called from outside the
 * office (webhooks, the public portal, SCIM, health checks, SAML/OAuth redirects) stay reachable; they have their
 * own authentication. Set {@code APP_SECURITY_IP_ALLOWLIST_DISABLED=true} to recover from a bad list.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IpAllowlistFilter extends OncePerRequestFilter {

    static final List<String> EXEMPT = List.of("/api/public/", "/api/health", "/api/metrics", "/api/integrations/",
            "/api/inbound/", "/scim/", "/api/auth/oauth/", "/api/auth/saml/", "/api/calendar/feed/");

    private final SecurityPolicy policy;
    private final RateLimitFilter clientIps;
    private final ObjectMapper json;

    public IpAllowlistFilter(SecurityPolicy policy, RateLimitFilter clientIps, ObjectMapper json) {
        this.policy = policy;
        this.clientIps = clientIps;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") && !path.startsWith("/scim/") || EXEMPT.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (policy.ipAllowed(clientIps.clientIp(request))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(),
                new ErrorResponse("Your organization only allows access from approved networks."));
    }
}
