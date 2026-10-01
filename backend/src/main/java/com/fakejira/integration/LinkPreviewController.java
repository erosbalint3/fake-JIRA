package com.fakejira.integration;

import com.fakejira.common.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rich previews for links in descriptions and comments: page title, description and image from OpenGraph tags,
 * plus embeddable players for Figma, YouTube and Google Docs/Sheets/Slides. The server fetches the page itself,
 * so only public https addresses are allowed (the same check as webhooks), redirects are re-checked, and responses
 * are size-limited and cached.
 */
@RestController
public class LinkPreviewController {

    static final int MAX_BYTES = 512 * 1024;
    static final Duration CACHE_FOR = Duration.ofHours(6);
    static final int CACHE_SIZE = 500;

    /** {@code embed}: an iframe URL for known services; null otherwise. */
    public record Preview(String url, String title, String description, String image, String siteName, String embed,
                          String kind) {
    }

    private record Cached(Preview preview, Instant expires) {
    }

    private final ChatSender urls;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, Cached> cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    public LinkPreviewController(ChatSender urls) {
        this.urls = urls;
    }

    @GetMapping("/api/link-preview")
    public Preview preview(@RequestParam String url) {
        String target = url.trim();
        if (target.length() > 2000) {
            throw ApiException.badRequest("That link is too long.");
        }
        Cached hit = cache.get(target);
        if (hit != null && hit.expires().isAfter(Instant.now())) {
            return hit.preview();
        }
        urls.checkUrl(target);
        Preview preview = fetch(target);
        cache.put(target, new Cached(preview, Instant.now().plus(CACHE_FOR)));
        return preview;
    }

    private Preview fetch(String url) {
        String[] embed = embed(url);
        String current = url;
        String html = null;
        try {
            for (int hop = 0; hop < 4 && html == null; hop++) {
                HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(current))
                        .timeout(Duration.ofSeconds(8)).header("User-Agent", "FakeJIRA-LinkPreview/1 (+https://github.com)")
                        .header("Accept", "text/html,application/xhtml+xml").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    response.body().close();
                    if (location == null) break;
                    current = URI.create(current).resolve(location).toString();
                    urls.checkUrl(current);
                    continue;
                }
                String type = response.headers().firstValue("Content-Type").orElse("");
                if (status >= 400 || !type.contains("html")) {
                    response.body().close();
                    break;
                }
                try (InputStream in = response.body()) {
                    html = read(in);
                }
            }
        } catch (ApiException e) {
            html = null;
        } catch (IOException e) {
            html = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String host = URI.create(url).getHost();
        if (html == null) {
            return new Preview(url, null, null, null, host, embed == null ? null : embed[0], embed == null ? "link" : embed[1]);
        }
        String title = first(meta(html, "og:title"), meta(html, "twitter:title"), tag(html, "title"));
        String description = first(meta(html, "og:description"), meta(html, "twitter:description"), meta(html, "description"));
        String image = first(meta(html, "og:image"), meta(html, "twitter:image"));
        if (image != null) {
            try {
                image = URI.create(current).resolve(image).toString();
                if (!image.startsWith("https://")) image = null;
            } catch (IllegalArgumentException e) {
                image = null;
            }
        }
        return new Preview(url, cut(title, 200), cut(description, 400), image, first(meta(html, "og:site_name"), host),
                embed == null ? null : embed[0], embed == null ? "link" : embed[1]);
    }

    /** Iframe URL and kind for services that can be embedded, or null. */
    static String[] embed(String url) {
        Matcher youtube = Pattern.compile("^https://(?:www\\.|m\\.)?(?:youtube\\.com/watch\\?(?:.*&)?v=|youtu\\.be/)([A-Za-z0-9_-]{11})").matcher(url);
        if (youtube.find()) {
            return new String[]{"https://www.youtube-nocookie.com/embed/" + youtube.group(1), "youtube"};
        }
        if (url.matches("^https://(?:www\\.)?figma\\.com/(?:file|design|proto|board|slides)/[A-Za-z0-9]+.*")) {
            return new String[]{"https://www.figma.com/embed?embed_host=fakejira&url=" + URLEncoder.encode(url, StandardCharsets.UTF_8), "figma"};
        }
        Matcher docs = Pattern.compile("^https://docs\\.google\\.com/(document|spreadsheets|presentation)/d/([A-Za-z0-9_-]+)").matcher(url);
        if (docs.find()) {
            return new String[]{"https://docs.google.com/" + docs.group(1) + "/d/" + docs.group(2) + "/preview", "google-" + docs.group(1)};
        }
        return null;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0 && out.size() < MAX_BYTES) {
            out.write(buffer, 0, Math.min(n, MAX_BYTES - out.size()));
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    static String meta(String html, String name) {
        Pattern pattern = Pattern.compile("<meta\\s[^>]*(?:property|name)\\s*=\\s*[\"']" + Pattern.quote(name) + "[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        Matcher content = Pattern.compile("content\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(matcher.group());
        return content.find() ? unescape(content.group(1)).trim() : null;
    }

    static String tag(String html, String name) {
        Matcher matcher = Pattern.compile("<" + name + "[^>]*>([^<]{1,500})</" + name + ">", Pattern.CASE_INSENSITIVE).matcher(html);
        return matcher.find() ? unescape(matcher.group(1)).trim() : null;
    }

    private static String unescape(String text) {
        return text.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String cut(String text, int max) {
        return text == null ? null : text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }
}
