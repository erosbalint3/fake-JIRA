package com.fakejira.auth;

import com.fakejira.admin.AppSettings;
import com.fakejira.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Password rules; admins adjust them on the Admin page. */
@Service
public class PasswordPolicy {

    public record Rules(int minLength, boolean upper, boolean digit, boolean special) {
        public Rules {
            minLength = Math.max(8, Math.min(64, minLength));
        }
    }

    static final String KEY = "password.policy";
    private static final Rules DEFAULT = new Rules(8, true, true, true);

    private final AppSettings settings;

    public PasswordPolicy(AppSettings settings) {
        this.settings = settings;
    }

    public Rules rules() {
        return settings.get(KEY).map(PasswordPolicy::parse).orElse(DEFAULT);
    }

    public void setRules(Rules rules) {
        settings.put(KEY, rules.minLength() + "," + rules.upper() + "," + rules.digit() + "," + rules.special());
    }

    private static Rules parse(String value) {
        String[] parts = value.split(",");
        try {
            return new Rules(Integer.parseInt(parts[0]), Boolean.parseBoolean(parts[1]), Boolean.parseBoolean(parts[2]),
                    Boolean.parseBoolean(parts[3]));
        } catch (RuntimeException e) {
            return DEFAULT;
        }
    }

    /** Throws a field error on {@code field} when the password breaks the rules. */
    public void check(String password, String field) {
        String problem = problem(password);
        if (problem != null) {
            throw ApiException.field(field, problem);
        }
    }

    /** What is wrong with the password, or null when it is fine. */
    public String problem(String password) {
        Rules rules = rules();
        if (password == null || password.length() < rules.minLength()) {
            return "Password must be at least " + rules.minLength() + " characters long";
        }
        if (password.length() > 100) {
            return "Password must be at most 100 characters long";
        }
        List<String> missing = new ArrayList<>();
        if (rules.upper() && password.chars().noneMatch(Character::isUpperCase)) {
            missing.add("an uppercase letter");
        }
        if (rules.digit() && password.chars().noneMatch(Character::isDigit)) {
            missing.add("a number");
        }
        if (rules.special() && password.chars().allMatch(Character::isLetterOrDigit)) {
            missing.add("a special character");
        }
        if (missing.isEmpty()) {
            return null;
        }
        return "Password needs " + String.join(", ", missing.subList(0, missing.size() - 1))
                + (missing.size() > 1 ? " and " : "") + missing.get(missing.size() - 1);
    }
}
