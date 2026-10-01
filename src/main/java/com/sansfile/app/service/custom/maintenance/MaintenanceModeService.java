package com.sansfile.app.service.custom.maintenance;

import com.sansfile.app.domain.PlatformSettings;
import com.sansfile.app.repository.PlatformSettingsRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Mode maintenance (Paramètres Système de l'admin), lu au plus une fois toutes les 5 s
 * pour ne pas interroger la base à chaque requête.
 */
@Service
public class MaintenanceModeService {

    private static final Duration CACHE_DURATION = Duration.ofSeconds(5);

    private final PlatformSettingsRepository platformSettingsRepository;

    private volatile CachedFlag cached;

    public MaintenanceModeService(PlatformSettingsRepository platformSettingsRepository) {
        this.platformSettingsRepository = platformSettingsRepository;
    }

    public boolean isActive() {
        CachedFlag flag = cached;
        if (flag == null || flag.readAt().plus(CACHE_DURATION).isBefore(Instant.now())) {
            boolean active = platformSettingsRepository
                .findAll()
                .stream()
                .findFirst()
                .map(PlatformSettings::getMaintenanceMode)
                .map(Boolean.TRUE::equals)
                .orElse(false);
            flag = new CachedFlag(active, Instant.now());
            cached = flag;
        }
        return flag.active();
    }

    /** Après une modification des paramètres : relu dès la requête suivante, puis encore à la fin de la transaction. */
    public void evict() {
        cached = null;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        cached = null;
                    }
                }
            );
        }
    }

    private record CachedFlag(boolean active, Instant readAt) {}
}
