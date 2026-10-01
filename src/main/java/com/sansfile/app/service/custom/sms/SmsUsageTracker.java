package com.sansfile.app.service.custom.sms;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Compteurs d'envoi SMS depuis le démarrage du serveur (supervision admin).
 * Ne conserve ni numéro ni contenu : uniquement des totaux et des horodatages.
 */
@Component
public class SmsUsageTracker {

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicReference<Instant> lastSentAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();

    public void record(boolean success) {
        if (success) {
            sent.incrementAndGet();
            lastSentAt.set(Instant.now());
        } else {
            failed.incrementAndGet();
            lastFailureAt.set(Instant.now());
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(sent.get(), failed.get(), lastSentAt.get(), lastFailureAt.get());
    }

    public record Snapshot(long sent, long failed, Instant lastSentAt, Instant lastFailureAt) {}
}
