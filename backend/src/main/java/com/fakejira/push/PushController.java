package com.fakejira.push;

import com.fakejira.common.CurrentUser;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushService push;
    private final PushSubscriptionRepository subscriptions;
    private final CurrentUser currentUser;

    public PushController(PushService push, PushSubscriptionRepository subscriptions, CurrentUser currentUser) {
        this.push = push;
        this.subscriptions = subscriptions;
        this.currentUser = currentUser;
    }

    public record Keys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 100) String auth) {
    }

    /** The shape of a browser's PushSubscription.toJSON(). */
    public record SubscribeRequest(@NotBlank @Size(max = 1000) String endpoint, @NotNull @Valid Keys keys) {
    }

    public record UnsubscribeRequest(@NotBlank String endpoint) {
    }

    /** Public: the application server key browsers need to subscribe. */
    @GetMapping("/key")
    public Map<String, String> key() {
        return Map.of("publicKey", push.publicKey());
    }

    @PostMapping("/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void subscribe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubscribeRequest request) {
        User user = currentUser.from(jwt);
        PushService.validateEndpoint(request.endpoint());
        try {
            if (Base64.getUrlDecoder().decode(request.keys().p256dh()).length != 65
                    || Base64.getUrlDecoder().decode(request.keys().auth()).length != 16) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw com.fakejira.common.ApiException.badRequest("Invalid subscription keys.");
        }
        subscriptions.findByEndpoint(request.endpoint()).ifPresentOrElse(existing -> {
            existing.setUser(user);
            existing.setKeys(request.keys().p256dh(), request.keys().auth());
        }, () -> subscriptions.save(new PushSubscription(user, request.endpoint(), request.keys().p256dh(),
                request.keys().auth())));
    }

    @DeleteMapping("/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void unsubscribe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UnsubscribeRequest request) {
        User user = currentUser.from(jwt);
        subscriptions.findByEndpoint(request.endpoint())
                .filter(s -> s.getUser().getId().equals(user.getId()))
                .ifPresent(subscriptions::delete);
    }

    public record TestResult(int delivered) {
    }

    @PostMapping("/test")
    public TestResult test(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return new TestResult(push.sendToUser(user.getId(),
                Map.of("title", "FakeJIRA", "body", "Push notifications work on this device.", "url", "/profile")));
    }
}
