package com.fakejira.preference;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.user.AccountService;
import com.fakejira.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Personal settings that follow people across devices: board card fields, saved views, appearance and the like.
 * Values are JSON chosen by the web app; the server only limits their size.
 */
@RestController
@Transactional
public class PreferenceController {

    static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9._:-]{0,99}$");
    static final int MAX_VALUE = 32 * 1024;
    static final int MAX_KEYS = 300;

    private final UserPreferenceRepository preferences;
    private final CurrentUser currentUser;
    private final ObjectMapper json;

    public PreferenceController(UserPreferenceRepository preferences, CurrentUser currentUser, ObjectMapper json) {
        this.preferences = preferences;
        this.currentUser = currentUser;
        this.json = json;
    }

    @GetMapping("/api/preferences")
    @Transactional(readOnly = true)
    public Map<String, JsonNode> all(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (UserPreference p : preferences.findByUserId(user.getId())) {
            result.put(p.getKey(), parse(p.getValue()));
        }
        return result;
    }

    @GetMapping("/api/preferences/{key}")
    @Transactional(readOnly = true)
    public JsonNode get(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        User user = currentUser.from(jwt);
        return preferences.findByUserIdAndKey(user.getId(), check(key)).map(p -> parse(p.getValue()))
                .orElse(json.nullNode());
    }

    @PutMapping("/api/preferences/{key}")
    public JsonNode put(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @RequestBody JsonNode value) {
        User user = currentUser.from(jwt);
        String text;
        try {
            text = json.writeValueAsString(value);
        } catch (IOException e) {
            throw ApiException.badRequest("Not valid JSON.");
        }
        if (text.length() > MAX_VALUE) {
            throw ApiException.badRequest("That setting is too large.");
        }
        var existing = preferences.findByUserIdAndKey(user.getId(), check(key));
        if (existing.isPresent()) {
            existing.get().setValue(text);
        } else {
            if (preferences.countByUserId(user.getId()) >= MAX_KEYS) {
                throw ApiException.badRequest("Too many saved settings.");
            }
            preferences.save(new UserPreference(user.getId(), key, text));
        }
        return value;
    }

    @DeleteMapping("/api/preferences/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        User user = currentUser.from(jwt);
        preferences.findByUserIdAndKey(user.getId(), check(key)).ifPresent(preferences::delete);
    }

    @EventListener
    public void onUserDeleting(AccountService.UserDeleting event) {
        preferences.deleteForUser(event.userId());
    }

    private static String check(String key) {
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest("Setting names are lowercase letters, digits and . _ : -");
        }
        return key;
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (IOException e) {
            return json.nullNode();
        }
    }
}
