package com.sansfile.app.service.custom.storage;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Compteurs de téléversement d'images depuis le démarrage du serveur (supervision admin).
 * Un échec Cloudinary n'empêche pas l'envoi (bascule sur le disque local) : seul ce compteur le rend visible.
 */
@Component
public class StorageUsageTracker {

    private final AtomicLong cloudinaryUploads = new AtomicLong();
    private final AtomicLong cloudinaryFailures = new AtomicLong();
    private final AtomicLong localUploads = new AtomicLong();
    private final AtomicReference<Instant> lastCloudinaryUploadAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastCloudinaryFailureAt = new AtomicReference<>();

    public void cloudinaryUploaded() {
        cloudinaryUploads.incrementAndGet();
        lastCloudinaryUploadAt.set(Instant.now());
    }

    public void cloudinaryFailed() {
        cloudinaryFailures.incrementAndGet();
        lastCloudinaryFailureAt.set(Instant.now());
    }

    public void storedLocally() {
        localUploads.incrementAndGet();
    }

    public Snapshot snapshot() {
        return new Snapshot(
            cloudinaryUploads.get(),
            cloudinaryFailures.get(),
            localUploads.get(),
            lastCloudinaryUploadAt.get(),
            lastCloudinaryFailureAt.get()
        );
    }

    public record Snapshot(
        long cloudinaryUploads,
        long cloudinaryFailures,
        long localUploads,
        Instant lastCloudinaryUploadAt,
        Instant lastCloudinaryFailureAt
    ) {}
}
