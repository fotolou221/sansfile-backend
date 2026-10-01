package com.sansfile.app.service.custom.access;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Anti force brute sur la connexion par mot de passe : un identifiant est verrouillé
 * après {@value #MAX_FAILURES} échecs sur une fenêtre de 15 minutes.
 */
@Service
public class LoginAttemptService {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_LOGINS = 10_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public boolean isLocked(String login) {
        Deque<Instant> attempts = failures.get(key(login));
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            prune(attempts);
            return attempts.size() >= MAX_FAILURES;
        }
    }

    public void recordFailure(String login) {
        if (failures.size() > MAX_TRACKED_LOGINS) {
            failures.values().removeIf(attempts -> {
                synchronized (attempts) {
                    prune(attempts);
                    return attempts.isEmpty();
                }
            });
        }
        Deque<Instant> attempts = failures.computeIfAbsent(key(login), k -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts);
            attempts.addLast(Instant.now());
        }
    }

    public void recordSuccess(String login) {
        failures.remove(key(login));
    }

    private static void prune(Deque<Instant> attempts) {
        Instant limit = Instant.now().minus(WINDOW);
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(limit)) {
            attempts.removeFirst();
        }
    }

    private static String key(String login) {
        return login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    }
}
