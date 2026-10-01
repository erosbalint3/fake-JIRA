package com.fakejira.admin;

import com.fakejira.common.ApiException;
import com.fakejira.config.JwtProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Organization-wide sign-in rules set by admins: require single sign-on, session length and idle timeout, the IP
 * allowlist, and new-device alerts. Stored as one JSON setting and cached in memory.
 */
@Service
public class SecurityPolicy {

    static final String KEY = "security.policy";

    /**
     * {@code sessionHours}: how long a sign-in lasts; {@code idleMinutes}: sign out after this long without activity
     * (0 = never); {@code ipAllowlist}: addresses or CIDR ranges people may use the app from (empty = anywhere).
     */
    public record Policy(boolean ssoRequired, int sessionHours, int idleMinutes, List<String> ipAllowlist,
                         boolean loginAlerts) {
    }

    /** A parsed address range. */
    public record Range(byte[] network, int prefix) {
        public boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (address[full] & mask) == (network[full] & mask);
        }
    }

    private final AppSettings settings;
    private final ObjectMapper json;
    private final Policy defaults;
    private final boolean allowlistDisabled;
    private volatile Policy current;
    private volatile List<Range> ranges = List.of();

    private final com.fakejira.cluster.Cluster cluster;

    public SecurityPolicy(AppSettings settings, ObjectMapper json, JwtProperties jwt,
                          @Value("${app.security.ip-allowlist-disabled:false}") boolean allowlistDisabled,
                          com.fakejira.cluster.Cluster cluster) {
        this.cluster = cluster;
        // Another instance saved new rules: read them again.
        cluster.subscribe("settings", key -> {
            if (KEY.equals(key)) current = null;
        });
        this.settings = settings;
        this.json = json;
        this.allowlistDisabled = allowlistDisabled;
        this.defaults = new Policy(false, (int) Math.max(1, jwt.validity().toHours()), 0, List.of(), true);
    }

    public Policy get() {
        Policy policy = current;
        if (policy == null) {
            policy = settings.get(KEY).map(this::read).orElse(defaults);
            ranges = parseAll(policy.ipAllowlist());
            current = policy;
        }
        return policy;
    }

    /** Validates and saves; {@code adminIp} must stay allowed so admins cannot lock themselves out. */
    public Policy save(Policy policy, String adminIp) {
        if (policy.sessionHours() < 1 || policy.sessionHours() > 24 * 90) {
            throw ApiException.field("sessionHours", "Between 1 hour and 90 days");
        }
        if (policy.idleMinutes() != 0 && (policy.idleMinutes() < 15 || policy.idleMinutes() > 24 * 60 * 30)) {
            throw ApiException.field("idleMinutes", "0 (off) or at least 15 minutes");
        }
        List<String> list = policy.ipAllowlist() == null ? List.of()
                : policy.ipAllowlist().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (list.size() > 200) {
            throw ApiException.field("ipAllowlist", "At most 200 entries");
        }
        List<Range> parsed;
        try {
            parsed = parseAll(list);
        } catch (IllegalArgumentException e) {
            throw ApiException.field("ipAllowlist", e.getMessage());
        }
        if (!parsed.isEmpty() && !matches(parsed, adminIp)) {
            throw ApiException.field("ipAllowlist", "Your own address (" + adminIp + ") must be on the list, or you would be locked out.");
        }
        Policy saved = new Policy(policy.ssoRequired(), policy.sessionHours(), policy.idleMinutes(), list, policy.loginAlerts());
        try {
            settings.put(KEY, json.writeValueAsString(saved));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        ranges = parsed;
        current = saved;
        if (cluster.enabled()) {
            cluster.publish("settings", KEY);
        }
        return saved;
    }

    public Duration sessionLength() {
        return Duration.ofHours(get().sessionHours());
    }

    /** True when the address may use the app (always, when there is no allowlist). */
    public boolean ipAllowed(String ip) {
        get();
        List<Range> list = ranges;
        return allowlistDisabled || list.isEmpty() || matches(list, ip);
    }

    private static boolean matches(List<Range> list, String ip) {
        byte[] address = address(ip);
        return address != null && list.stream().anyMatch(r -> r.contains(address));
    }

    static List<Range> parseAll(List<String> entries) {
        List<Range> list = new ArrayList<>();
        for (String entry : entries) {
            list.add(parse(entry));
        }
        return list;
    }

    public static Range parse(String entry) {
        String[] parts = entry.trim().split("/", 2);
        byte[] network = literal(parts[0]);
        if (network == null) {
            throw new IllegalArgumentException("“" + entry + "” is not an IP address or range");
        }
        int prefix = network.length * 8;
        if (parts.length == 2) {
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("“" + entry + "” has a bad prefix length");
            }
            if (prefix < 0 || prefix > network.length * 8) {
                throw new IllegalArgumentException("“" + entry + "” has a bad prefix length");
            }
        }
        return new Range(network, prefix);
    }

    /** Parses an IP literal without any DNS lookup; IPv4-mapped IPv6 addresses count as IPv4. */
    public static byte[] address(String ip) {
        byte[] bytes = literal(ip);
        if (bytes != null && bytes.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
            mapped &= bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
            if (mapped) {
                return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
            }
        }
        return bytes;
    }

    private static byte[] literal(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        boolean ipv4 = value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean ipv6 = value.contains(":") && value.matches("[0-9A-Fa-f:.]+");
        if (!ipv4 && !ipv6) {
            return null;
        }
        if (ipv4) {
            for (String octet : value.split("\\.")) {
                if (Integer.parseInt(octet) > 255) return null;
            }
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private Policy read(String text) {
        try {
            Policy stored = json.readValue(text, Policy.class);
            return new Policy(stored.ssoRequired(), stored.sessionHours() > 0 ? stored.sessionHours() : defaults.sessionHours(),
                    stored.idleMinutes(), stored.ipAllowlist() == null ? List.of() : stored.ipAllowlist(), stored.loginAlerts());
        } catch (JsonProcessingException e) {
            return defaults;
        }
    }
}
