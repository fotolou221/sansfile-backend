package com.sansfile.app.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limitation de débit par adresse IP sur les points d'entrée sensibles (force brute, envoi massif de SMS, spam).
 * Derrière Nginx, l'adresse réelle du client est fournie par X-Forwarded-For (forward-headers-strategy: native).
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitingFilter.class);
    private static final int MAX_TRACKED_KEYS = 50_000;

    private record Rule(String method, String path, int limit, Duration window) {}

    private static final List<Rule> RULES = List.of(
        new Rule("POST", "/api/authenticate", 10, Duration.ofMinutes(15)),
        new Rule("POST", "/api/agent/password", 10, Duration.ofMinutes(15)),
        new Rule("POST", "/api/auth/otp/send", 10, Duration.ofHours(1)),
        new Rule("POST", "/api/auth/otp/verify", 20, Duration.ofMinutes(15)),
        new Rule("POST", "/api/auth/refresh", 60, Duration.ofMinutes(15)),
        new Rule("POST", "/api/orders/checkout", 20, Duration.ofHours(1)),
        new Rule("POST", "/api/tickets/book-multiple", 30, Duration.ofHours(1)),
        new Rule("POST", "/api/storage/upload", 60, Duration.ofHours(1)),
        new Rule("POST", "/api/files/upload", 60, Duration.ofHours(1))
    );

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        Rule rule = matchingRule(request);
        if (rule != null && !tryAcquire(rule.path() + "|" + request.getRemoteAddr(), rule)) {
            LOG.warn("⛔ Limite atteinte sur {} pour {}", rule.path(), request.getRemoteAddr());
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(rule.window().toSeconds()));
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Trop de tentatives. Réessayez dans quelques minutes.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static Rule matchingRule(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (Rule rule : RULES) {
            if (rule.method().equalsIgnoreCase(request.getMethod()) && rule.path().equals(uri)) {
                return rule;
            }
        }
        return null;
    }

    private boolean tryAcquire(String key, Rule rule) {
        if (hits.size() > MAX_TRACKED_KEYS) {
            purgeExpired();
        }
        long now = System.currentTimeMillis();
        long windowStart = now - rule.window().toMillis();
        Deque<Long> timestamps = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= rule.limit()) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    private void purgeExpired() {
        long oldestUseful = System.currentTimeMillis() - Duration.ofHours(1).toMillis();
        hits.values().removeIf(timestamps -> {
            synchronized (timestamps) {
                return timestamps.isEmpty() || timestamps.peekLast() < oldestUseful;
            }
        });
    }
}
