package com.fakejira.task;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds @username mentions in comment text. */
final class MentionParser {

    // Not preceded by a word character, so emails like bob@example.com are not mentions.
    private static final Pattern MENTION = Pattern.compile("(?<![\\w.])@([A-Za-z0-9._-]{2,40})");

    private MentionParser() {
    }

    static Set<String> usernames(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1).replaceAll("[.\\-_]+$", "");
            if (!name.isEmpty()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
}
