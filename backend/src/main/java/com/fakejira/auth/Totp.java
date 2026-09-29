package com.fakejira.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;

/** Time-based one-time passwords (RFC 6238: HMAC-SHA1, 30-second steps, 6 digits), as used by authenticator apps. */
public final class Totp {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int STEP_SECONDS = 30;

    private Totp() {
    }

    /** A new random 160-bit secret, base32-encoded. */
    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    public static String otpauthUrl(String secret, String account) {
        String label = URLEncoder.encode("FakeJIRA:" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=FakeJIRA&algorithm=SHA1&digits=6&period=30";
    }

    public static long currentStep() {
        return Instant.now().getEpochSecond() / STEP_SECONDS;
    }

    /**
     * Returns the step the code matches (allowing one step of clock drift either way), or -1. Steps at or
     * before {@code lastUsedStep} are refused so a code cannot be replayed.
     */
    public static long verify(String secret, String code, Long lastUsedStep, long now) {
        String digits = code == null ? "" : code.replaceAll("\\s", "");
        if (!digits.matches("\\d{6}")) {
            return -1;
        }
        for (long step = now - 1; step <= now + 1; step++) {
            if (lastUsedStep != null && step <= lastUsedStep) {
                continue;
            }
            if (constantTimeEquals(code(secret, step), digits)) {
                return step;
            }
        }
        return -1;
    }

    public static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(unbase32(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] unbase32(String text) {
        String clean = text.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }
}
