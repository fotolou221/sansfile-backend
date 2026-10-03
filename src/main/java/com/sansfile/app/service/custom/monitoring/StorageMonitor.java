package com.sansfile.app.service.custom.monitoring;

import com.cloudinary.Cloudinary;
import com.cloudinary.api.exceptions.ApiException;
import com.cloudinary.api.exceptions.RateLimited;
import com.cloudinary.utils.ObjectUtils;
import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.service.custom.monitoring.MonitoringService.CloudinaryUsage;
import com.sansfile.app.service.custom.monitoring.MonitoringService.ComponentStatus;
import com.sansfile.app.service.custom.monitoring.MonitoringService.StorageInfo;
import com.sansfile.app.service.custom.storage.StorageUsageTracker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Supervision du stockage des images : compte Cloudinary (Admin API « usage », lecture seule)
 * et dossier d'upload local. Les deux sont mis en cache pour ménager la limite d'appels Cloudinary.
 */
@Component
public class StorageMonitor {

    /** Au-delà, l'admin est prévenu avant que Cloudinary ne bloque les téléversements. */
    static final double QUOTA_WARNING_PERCENT = 80;

    private static final Duration CLOUDINARY_CACHE = Duration.ofMinutes(2);
    /** Compteur en direct : au plus 120 appels Search par heure, loin de la limite Admin API (500/h en Free). */
    private static final Duration LIVE_RESOURCES_CACHE = Duration.ofSeconds(30);
    private static final Duration LOCAL_STATS_CACHE = Duration.ofMinutes(5);
    private static final int CLOUDINARY_TIMEOUT_SECONDS = 10;

    private final Cloudinary cloudinary;
    private final ApplicationProperties applicationProperties;
    private final StorageUsageTracker usageTracker;

    private volatile Cached<CloudinaryCheck> cloudinaryCache;
    private volatile Cached<Long> liveResourcesCache;
    private volatile Cached<LocalStats> localCache;

    public StorageMonitor(Cloudinary cloudinary, ApplicationProperties applicationProperties, StorageUsageTracker usageTracker) {
        this.cloudinary = cloudinary;
        this.applicationProperties = applicationProperties;
        this.usageTracker = usageTracker;
    }

    StorageInfo storageInfo() {
        boolean selected = "cloudinary".equalsIgnoreCase(applicationProperties.getStorage().getProvider());
        boolean configured = isCloudinaryConfigured();
        CloudinaryCheck check = configured ? cloudinaryCheck() : null;
        // Le rapport « usage » n'est recalculé qu'une fois par jour : le nombre de fichiers est relu en direct
        Long liveResources = check != null && check.error() == null ? cloudinaryLiveResources() : null;
        LocalStats local = localStats();
        StorageUsageTracker.Snapshot uploads = usageTracker.snapshot();
        return new StorageInfo(
            selected ? "cloudinary" : "local",
            configured,
            configured ? cloudinary.config.cloudName : null,
            check != null ? check.usage() : null,
            check != null ? check.error() : null,
            local.writable(),
            local.files(),
            Math.round((local.bytes() / (1024.0 * 1024)) * 10) / 10.0,
            uploads.cloudinaryUploads(),
            uploads.cloudinaryFailures(),
            uploads.localUploads(),
            uploads.lastCloudinaryUploadAt(),
            uploads.lastCloudinaryFailureAt(),
            liveResources
        );
    }

    ComponentStatus status(StorageInfo storage, List<String> warnings) {
        String label = "Stockage des images";
        if ("local".equals(storage.provider())) {
            if (!storage.localWritable()) {
                // Composant en panne : son détail est repris en tête des alertes par MonitoringService
                return new ComponentStatus(
                    "storage",
                    label,
                    MonitoringService.DOWN,
                    "dossier des images non accessible en écriture : les téléversements échouent",
                    null
                );
            }
            return new ComponentStatus(
                "storage",
                label,
                MonitoringService.UP,
                "sur le serveur : %d image(s), %.1f Mo".formatted(storage.localFiles(), storage.localSizeMb()),
                null
            );
        }

        if (!storage.cloudinaryConfigured()) {
            warnings.add(
                "Cloudinary est choisi (STORAGE_PROVIDER=cloudinary) mais ses clés sont absentes : les images sont enregistrées sur le serveur."
            );
            return new ComponentStatus(
                "storage",
                label,
                MonitoringService.WARN,
                "Cloudinary sans clés : images enregistrées sur le serveur",
                null
            );
        }
        if (storage.cloudinaryError() != null) {
            warnings.add("Cloudinary " + storage.cloudinaryError() + " : les nouvelles images sont enregistrées sur le serveur.");
            return new ComponentStatus("storage", label, MonitoringService.WARN, "Cloudinary " + storage.cloudinaryError(), null);
        }

        CloudinaryUsage usage = storage.cloudinary();
        Double percent = usage.creditsUsedPercent();
        if (percent != null && percent >= 100) {
            return new ComponentStatus(
                "storage",
                label,
                MonitoringService.DOWN,
                "quota Cloudinary dépassé (forfait " + usage.plan() + ") : téléversements et affichage des images peuvent être bloqués",
                usage.latencyMs()
            );
        }
        if (percent != null && percent >= QUOTA_WARNING_PERCENT) {
            warnings.add("Quota Cloudinary utilisé à %.0f %% (forfait %s).".formatted(percent, usage.plan()));
            return new ComponentStatus(
                "storage",
                label,
                MonitoringService.WARN,
                "%.0f %% du quota utilisé".formatted(percent),
                usage.latencyMs()
            );
        }
        boolean lastUploadFailed =
            storage.lastCloudinaryFailureAt() != null &&
            (storage.lastCloudinaryUploadAt() == null || storage.lastCloudinaryFailureAt().isAfter(storage.lastCloudinaryUploadAt()));
        if (lastUploadFailed) {
            warnings.add("Le dernier envoi vers Cloudinary a échoué : l'image a été enregistrée sur le serveur.");
            return new ComponentStatus("storage", label, MonitoringService.WARN, "dernier envoi Cloudinary en échec", usage.latencyMs());
        }
        String quota = percent != null ? " · %.0f %% du quota".formatted(percent) : "";
        return new ComponentStatus(
            "storage",
            label,
            MonitoringService.UP,
            "Cloudinary répond en " + usage.latencyMs() + " ms" + quota,
            usage.latencyMs()
        );
    }

    // ── Cloudinary ──────────────────────────────────────────────

    private boolean isCloudinaryConfigured() {
        var config = cloudinary.config;
        return notBlank(config.cloudName) && notBlank(config.apiKey) && notBlank(config.apiSecret);
    }

    private CloudinaryCheck cloudinaryCheck() {
        Cached<CloudinaryCheck> cached = cloudinaryCache;
        if (cached == null || cached.isOlderThan(CLOUDINARY_CACHE)) {
            cached = new Cached<>(fetchCloudinaryUsage(), Instant.now());
            cloudinaryCache = cached;
        }
        return cached.value();
    }

    private CloudinaryCheck fetchCloudinaryUsage() {
        long start = System.nanoTime();
        // Options par appel en millisecondes (contrairement au « timeout » global du SDK, en secondes)
        int timeoutMs = CLOUDINARY_TIMEOUT_SECONDS * 1000;
        try {
            Map<?, ?> response = withTimeout(() ->
                cloudinary.api().usage(ObjectUtils.asMap("timeout", timeoutMs, "connect_timeout", timeoutMs))
            );
            long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return new CloudinaryCheck(toUsage(response, latency), null);
        } catch (TimeoutException e) {
            return new CloudinaryCheck(null, "ne répond pas (plus de " + CLOUDINARY_TIMEOUT_SECONDS + " s)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CloudinaryCheck(null, "vérification interrompue");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RateLimited) {
                return new CloudinaryCheck(null, "a atteint sa limite d'appels API (réessai automatique)");
            }
            if (cause instanceof ApiException) {
                return new CloudinaryCheck(null, "refuse la connexion (clés API invalides ?)");
            }
            return new CloudinaryCheck(null, "injoignable (" + cause.getClass().getSimpleName() + ")");
        }
    }

    private Long cloudinaryLiveResources() {
        Cached<Long> cached = liveResourcesCache;
        if (cached == null || cached.isOlderThan(LIVE_RESOURCES_CACHE)) {
            cached = new Cached<>(fetchCloudinaryLiveResources(), Instant.now());
            liveResourcesCache = cached;
        }
        return cached.value();
    }

    /** API Search sans critère : son « total_count » compte tous les fichiers du compte, mis à jour en quelques secondes. */
    private Long fetchCloudinaryLiveResources() {
        try {
            return toTotalCount(withTimeout(() -> cloudinary.search().maxResults(1).execute()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            // L'admin garde le chiffre du rapport quotidien
            return null;
        }
    }

    static Long toTotalCount(Map<?, ?> searchResponse) {
        return toLong(searchResponse.get("total_count"));
    }

    /** Le SDK ne coupe pas toujours une connexion bloquée : l'attente est bornée ici. */
    private static <T> T withTimeout(Callable<T> call) throws InterruptedException, ExecutionException, TimeoutException {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).get(CLOUDINARY_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS);
    }

    /** Réponse de GET /usage : les forfaits à crédits exposent « credits », les anciens forfaits un pourcentage par ressource. */
    static CloudinaryUsage toUsage(Map<?, ?> response, long latencyMs) {
        Map<?, ?> credits = asMap(response.get("credits"));
        Map<?, ?> storage = asMap(response.get("storage"));
        Map<?, ?> bandwidth = asMap(response.get("bandwidth"));
        Map<?, ?> transformations = asMap(response.get("transformations"));
        Double usedPercent = toDouble(credits.get("used_percent"));
        if (usedPercent == null) {
            usedPercent = Stream.of(storage, bandwidth, transformations)
                .map(m -> toDouble(m.get("used_percent")))
                .filter(v -> v != null)
                .max(Double::compare)
                .orElse(null);
        }
        return new CloudinaryUsage(
            response.get("plan") != null ? response.get("plan").toString() : "inconnu",
            toDouble(credits.get("usage")),
            toDouble(credits.get("limit")),
            usedPercent,
            toLong(storage.get("usage")),
            toLong(bandwidth.get("usage")),
            toLong(transformations.get("usage")),
            toLong(response.get("resources")),
            response.get("last_updated") != null ? response.get("last_updated").toString() : null,
            latencyMs
        );
    }

    // ── Stockage local ──────────────────────────────────────────

    private LocalStats localStats() {
        Cached<LocalStats> cached = localCache;
        if (cached == null || cached.isOlderThan(LOCAL_STATS_CACHE)) {
            cached = new Cached<>(scanUploadDirectory(), Instant.now());
            localCache = cached;
        }
        return cached.value();
    }

    private LocalStats scanUploadDirectory() {
        Path root = Paths.get(applicationProperties.getStorage().getUploadDir()).toAbsolutePath().normalize();
        boolean writable = Files.isDirectory(root) && Files.isWritable(root);
        if (!Files.isDirectory(root)) {
            return new LocalStats(writable, 0, 0);
        }
        long[] totals = new long[2];
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                totals[0]++;
                try {
                    totals[1] += Files.size(file);
                } catch (Exception e) {
                    // fichier supprimé pendant le parcours
                }
            });
        } catch (Exception e) {
            // dossier illisible : les compteurs restent partiels
        }
        return new LocalStats(writable, totals[0], totals[1]);
    }

    // ── Outils ──────────────────────────────────────────────────

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record CloudinaryCheck(CloudinaryUsage usage, String error) {}

    private record LocalStats(boolean writable, long files, long bytes) {}

    private record Cached<T>(T value, Instant fetchedAt) {
        boolean isOlderThan(Duration maxAge) {
            return fetchedAt.plus(maxAge).isBefore(Instant.now());
        }
    }
}
