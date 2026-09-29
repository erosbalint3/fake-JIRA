package com.fakejira.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Counts API requests by method and status class, and their total duration, for /api/metrics. */
@Component
public class RequestMetrics extends OncePerRequestFilter {

    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
    private final LongAdder durationMs = new LongAdder();
    private final AtomicLong inFlight = new AtomicLong();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/events") || path.equals("/api/metrics");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        inFlight.incrementAndGet();
        try {
            chain.doFilter(request, response);
        } finally {
            inFlight.decrementAndGet();
            durationMs.add((System.nanoTime() - start) / 1_000_000);
            String key = request.getMethod() + " " + (response.getStatus() / 100) + "xx";
            counts.computeIfAbsent(key, k -> new LongAdder()).increment();
        }
    }

    public Map<String, LongAdder> counts() {
        return counts;
    }

    public long totalDurationMs() {
        return durationMs.sum();
    }

    public long inFlight() {
        return inFlight.get();
    }
}
