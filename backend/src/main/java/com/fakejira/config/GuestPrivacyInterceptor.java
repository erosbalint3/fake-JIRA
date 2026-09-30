package com.fakejira.config;

import com.fakejira.project.ProjectRepository;
import com.fakejira.user.GuestPrivacy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Marks requests made by project guests so user summaries leave out other people's email addresses. */
@Configuration
public class GuestPrivacyInterceptor implements AsyncHandlerInterceptor, WebMvcConfigurer {

    private final ProjectRepository projects;

    public GuestPrivacyInterceptor(ProjectRepository projects) {
        this.projects = projects;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            try {
                Long userId = Long.valueOf(token.getToken().getSubject());
                if (projects.isGuestAnywhere(userId)) {
                    GuestPrivacy.set(userId);
                }
            } catch (NumberFormatException ignored) {
                // not a user token
            }
        }
        return true;
    }

    /** Streams (live events) hand the thread back early: clear the flag so the next request starts clean. */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        GuestPrivacy.clear();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        GuestPrivacy.clear();
    }
}
