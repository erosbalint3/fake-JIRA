package com.fakejira.servicedesk;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Finds likely duplicates: TF-IDF weighted cosine similarity over words (accents folded, short and common
 * words dropped). Good enough to catch "Login button broken" vs "Can't log in, button does nothing".
 */
public final class Similarity {

    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "have", "has", "not", "are", "was", "were", "but",
            "can", "cannot", "cant", "does", "doesnt", "dont", "when", "what", "which", "into", "onto", "our", "your",
            "you", "they", "them", "there", "then", "than", "too", "very", "just", "also", "any", "all", "some", "please",
            "egy", "hogy", "nem", "van", "meg", "vagy", "mint", "csak", "ami", "amit", "ezt", "azt", "kell", "nincs");

    private Similarity() {
    }

    public record Match<T>(T item, double score) {
    }

    /** Lower-case word stems (crude: common English endings trimmed) of at least three letters. */
    static List<String> words(String text) {
        String folded = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String word : folded.split("[^a-z0-9]+")) {
            if (word.length() < 3 || STOP.contains(word)) {
                continue;
            }
            out.add(stem(word));
        }
        return out;
    }

    static String stem(String word) {
        for (String suffix : new String[]{"ing", "ed", "es", "s"}) {
            if (word.length() > suffix.length() + 3 && word.endsWith(suffix)) {
                return word.substring(0, word.length() - suffix.length());
            }
        }
        return word;
    }

    /** The {@code limit} candidates most similar to {@code query}, scoring at least {@code threshold} (0–1). */
    public static <T> List<Match<T>> rank(String query, List<T> candidates, Function<T, String> text, double threshold, int limit) {
        List<String> queryWords = words(query);
        if (queryWords.isEmpty() || candidates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Integer>> docs = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (T candidate : candidates) {
            Map<String, Integer> counts = counts(words(text.apply(candidate)));
            docs.add(counts);
            counts.keySet().forEach(w -> documentFrequency.merge(w, 1, Integer::sum));
        }
        int n = candidates.size();
        Function<String, Double> idf = w -> Math.log(1.0 + (n + 1.0) / (documentFrequency.getOrDefault(w, 0) + 1.0));
        Map<String, Double> queryVector = weigh(counts(queryWords), idf);
        List<Match<T>> matches = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double score = cosine(queryVector, weigh(docs.get(i), idf));
            if (score >= threshold) {
                matches.add(new Match<>(candidates.get(i), Math.round(score * 100) / 100.0));
            }
        }
        matches.sort(Comparator.comparingDouble((Match<T> m) -> m.score()).reversed());
        return matches.size() > limit ? matches.subList(0, limit) : matches;
    }

    private static Map<String, Integer> counts(List<String> words) {
        Map<String, Integer> counts = new HashMap<>();
        words.forEach(w -> counts.merge(w, 1, Integer::sum));
        return counts;
    }

    private static Map<String, Double> weigh(Map<String, Integer> counts, Function<String, Double> idf) {
        Map<String, Double> vector = new HashMap<>();
        counts.forEach((w, c) -> vector.put(w, (1 + Math.log(c)) * idf.apply(w)));
        return vector;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) {
            Double other = b.get(e.getKey());
            if (other != null) {
                dot += e.getValue() * other;
            }
        }
        if (dot == 0) {
            return 0;
        }
        return dot / (norm(a) * norm(b));
    }

    private static double norm(Map<String, Double> v) {
        double sum = 0;
        for (double x : v.values()) {
            sum += x * x;
        }
        return Math.sqrt(sum);
    }
}
