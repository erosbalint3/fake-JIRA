package com.fakejira.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Copies each backup off the server: to S3-compatible storage (AWS S3, Cloudflare R2, Backblaze B2, MinIO…) and/or
 * a WebDAV folder (Nextcloud, ownCloud, a NAS). Old copies are not deleted here; use the bucket's lifecycle rules.
 */
@Service
public class OffsiteBackup {

    private static final Logger log = LoggerFactory.getLogger(OffsiteBackup.class);
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String s3Endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final String prefix;
    private final boolean pathStyle;
    private final String webdavUrl;
    private final String webdavUser;
    private final String webdavPassword;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private volatile Instant lastUploadAt;
    private volatile String lastFile;
    private volatile String lastError;

    public OffsiteBackup(@Value("${app.backup.s3.endpoint:}") String s3Endpoint,
                         @Value("${app.backup.s3.bucket:}") String bucket,
                         @Value("${app.backup.s3.region:us-east-1}") String region,
                         @Value("${app.backup.s3.access-key:}") String accessKey,
                         @Value("${app.backup.s3.secret-key:}") String secretKey,
                         @Value("${app.backup.s3.prefix:fakejira/}") String prefix,
                         @Value("${app.backup.s3.path-style:true}") boolean pathStyle,
                         @Value("${app.backup.webdav.url:}") String webdavUrl,
                         @Value("${app.backup.webdav.username:}") String webdavUser,
                         @Value("${app.backup.webdav.password:}") String webdavPassword) {
        this.s3Endpoint = trimSlash(s3Endpoint.trim());
        this.bucket = bucket.trim();
        this.region = region.trim().isEmpty() ? "us-east-1" : region.trim();
        this.accessKey = accessKey.trim();
        this.secretKey = secretKey.trim();
        this.prefix = prefix.trim();
        this.pathStyle = pathStyle;
        this.webdavUrl = webdavUrl.trim();
        this.webdavUser = webdavUser;
        this.webdavPassword = webdavPassword;
    }

    public record Status(boolean s3, String s3Target, boolean webdav, String webdavTarget, Instant lastUploadAt,
                         String lastFile, String lastError) {
    }

    public boolean s3Configured() {
        return !s3Endpoint.isEmpty() && !bucket.isEmpty() && !accessKey.isEmpty() && !secretKey.isEmpty();
    }

    public boolean webdavConfigured() {
        return !webdavUrl.isEmpty();
    }

    public boolean isConfigured() {
        return s3Configured() || webdavConfigured();
    }

    public Status status() {
        return new Status(s3Configured(), s3Configured() ? s3Endpoint + "/" + bucket + "/" + prefix : null,
                webdavConfigured(), webdavConfigured() ? webdavUrl : null, lastUploadAt, lastFile, lastError);
    }

    /** Uploads in the background (called after each backup). */
    @Async
    public void uploadLater(Path file) {
        upload(file);
    }

    /** Uploads to every configured target; returns null on success or the error(s). */
    public synchronized String upload(Path file) {
        if (!isConfigured()) {
            return null;
        }
        List<String> errors = new ArrayList<>();
        if (s3Configured()) {
            String error = s3Put(file);
            if (error != null) {
                errors.add("S3: " + error);
            }
        }
        if (webdavConfigured()) {
            String error = webdavPut(file);
            if (error != null) {
                errors.add("WebDAV: " + error);
            }
        }
        lastUploadAt = Instant.now();
        lastFile = file.getFileName().toString();
        lastError = errors.isEmpty() ? null : String.join("; ", errors);
        if (lastError == null) {
            log.info("Backup {} copied off-site", lastFile);
        } else {
            log.warn("Off-site backup of {} failed: {}", lastFile, lastError);
        }
        return lastError;
    }

    String s3Put(Path file) {
        try {
            String key = prefix + file.getFileName();
            URI endpoint = URI.create(s3Endpoint);
            String host = pathStyle ? hostOf(endpoint) : bucket + "." + hostOf(endpoint);
            String path = (pathStyle ? "/" + encodePath(bucket) : "") + "/" + encodePath(key);
            URI uri = URI.create(endpoint.getScheme() + "://" + host + path);
            String amzDate = AMZ_DATE.format(Instant.now());
            Map<String, String> headers = new TreeMap<>();
            headers.put("host", host);
            headers.put("x-amz-content-sha256", "UNSIGNED-PAYLOAD");
            headers.put("x-amz-date", amzDate);
            String authorization = authorization("PUT", path, "", headers, "UNSIGNED-PAYLOAD", amzDate, region, "s3",
                    accessKey, secretKey);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(30))
                    .header("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
                    .header("x-amz-date", amzDate)
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/zip")
                    .PUT(HttpRequest.BodyPublishers.ofFile(file))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2 ? null : "HTTP " + response.statusCode() + " " + shorten(response.body());
        } catch (IOException | RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
    }

    String webdavPut(Path file) {
        try {
            URI uri = URI.create(trimSlash(webdavUrl) + "/" + encodePath(file.getFileName().toString()));
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30))
                    .header("Content-Type", "application/zip")
                    .PUT(HttpRequest.BodyPublishers.ofFile(file));
            if (!webdavUser.isEmpty()) {
                request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (webdavUser + ":" + webdavPassword).getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2 ? null : "HTTP " + response.statusCode() + " " + shorten(response.body());
        } catch (IOException | RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
    }

    /** AWS Signature Version 4 "Authorization" header value. {@code headers} are lower-case names. */
    static String authorization(String method, String canonicalUri, String canonicalQuery, Map<String, String> headers,
                                String payloadHash, String amzDate, String region, String service, String accessKey,
                                String secretKey) {
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String signedHeaders = String.join(";", new TreeMap<>(headers).keySet());
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature(method, canonicalUri, canonicalQuery, headers, payloadHash, amzDate, region,
                service, secretKey);
    }

    static String signature(String method, String canonicalUri, String canonicalQuery, Map<String, String> headers,
                            String payloadHash, String amzDate, String region, String service, String secretKey) {
        StringBuilder canonicalHeaders = new StringBuilder();
        new TreeMap<>(headers).forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v.trim()).append('\n'));
        String signedHeaders = String.join(";", new TreeMap<>(headers).keySet());
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest));
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        return hex(hmac(key, stringToSign));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(String data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** URI-encodes each path segment the way SigV4 expects (keeps "/"). */
    static String encodePath(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            segments.add(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
                    .replace("%7E", "~"));
        }
        return String.join("/", segments);
    }

    private static String hostOf(URI uri) {
        return uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String shorten(String body) {
        String flat = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }
}
