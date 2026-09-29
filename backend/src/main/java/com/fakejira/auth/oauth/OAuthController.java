package com.fakejira.auth.oauth;

import com.fakejira.common.CurrentUser;
import com.fakejira.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
public class OAuthController {

    private static final String NONCE_COOKIE = "fj_oauth";

    private final OAuthService oauth;
    private final CurrentUser currentUser;

    public OAuthController(OAuthService oauth, CurrentUser currentUser) {
        this.oauth = oauth;
        this.currentUser = currentUser;
    }

    public record UrlRequest(String invite) {
    }

    /** Public: which "Sign in with …" buttons to show. */
    @GetMapping("/api/auth/providers")
    public List<Map<String, String>> providers() {
        return oauth.enabled();
    }

    /**
     * The URL to send the browser to. With a sign-in token, connects the provider to the current account
     * instead of signing in. A cookie ties the flow to this browser.
     */
    @PostMapping("/api/auth/oauth/{provider}/url")
    public ResponseEntity<Map<String, String>> url(@AuthenticationPrincipal Jwt jwt, @PathVariable String provider,
                                                   @RequestBody(required = false) UrlRequest request,
                                                   HttpServletRequest http) {
        User linkUser = jwt == null ? null : currentUser.from(jwt);
        String nonce = oauth.newNonce();
        String url = oauth.authorizationUrl(provider, linkUser, request == null ? null : request.invite(), nonce);
        ResponseCookie cookie = ResponseCookie.from(NONCE_COOKIE, nonce)
                .httpOnly(true)
                .secure(isHttps(http))
                .sameSite("Lax")
                .path("/api/auth/oauth")
                .maxAge(Duration.ofMinutes(10))
                .build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(Map.of("url", url));
    }

    /** The provider redirects here; we redirect on to the app with the outcome in the URL fragment. */
    @GetMapping("/api/auth/oauth/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @CookieValue(name = NONCE_COOKIE, required = false) String nonce) {
        OAuthService.Outcome outcome = error != null
                ? new OAuthService.Outcome("error=" + java.net.URLEncoder.encode("Sign-in was cancelled.",
                java.nio.charset.StandardCharsets.UTF_8))
                : oauth.callback(provider, code, state, nonce);
        ResponseCookie clear = ResponseCookie.from(NONCE_COOKIE, "").path("/api/auth/oauth").maxAge(0).build();
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, clear.toString())
                .header("Referrer-Policy", "no-referrer")
                .location(URI.create("/oauth-complete#" + outcome.fragment()))
                .build();
    }

    @DeleteMapping("/api/profile/identities/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@AuthenticationPrincipal Jwt jwt, @PathVariable String provider) {
        oauth.unlink(currentUser.from(jwt), provider);
    }

    private static boolean isHttps(HttpServletRequest request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        return request.isSecure() || (proto != null && proto.split(",")[0].trim().equalsIgnoreCase("https"));
    }
}
