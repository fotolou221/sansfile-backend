package com.sansfile.app.service.custom.monitoring;

import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.repository.OtpVerificationRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.service.custom.realtime.RealtimeEventService;
import com.sansfile.app.service.custom.sms.SendTextSmsProvider;
import com.sansfile.app.service.custom.sms.SmsUsageTracker;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Supervision de la plateforme pour la console admin : santé des composants, ressources du serveur
 * et abonnement SMS. Les contrôles sont faits à la demande (aucune tâche de fond).
 */
@Service
public class MonitoringService {

    public static final String UP = "UP";
    public static final String WARN = "WARN";
    public static final String DOWN = "DOWN";
    public static final String INFO = "INFO";

    /** En dessous, l'admin est prévenu qu'il faut recharger le compte SendText. */
    static final long LOW_SMS_BALANCE = 200;
    static final int EXPIRY_WARNING_DAYS = 7;
    static final double LOW_DISK_RATIO = 0.10;

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Dakar");
    private static final Duration BALANCE_CACHE = Duration.ofSeconds(60);
    private static final String REDIS_PING_KEY = "sansfile:monitoring:ping";

    private final DataSource dataSource;
    private final CacheManager cacheManager;
    private final ApplicationProperties applicationProperties;
    private final SendTextSmsProvider sendTextSmsProvider;
    private final SmsUsageTracker smsUsageTracker;
    private final OtpVerificationRepository otpVerificationRepository;
    private final RealtimeEventService realtimeEventService;
    private final Environment environment;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final StorageMonitor storageMonitor;

    private volatile CachedBalance cachedBalance;

    public MonitoringService(
        DataSource dataSource,
        CacheManager cacheManager,
        ApplicationProperties applicationProperties,
        SendTextSmsProvider sendTextSmsProvider,
        SmsUsageTracker smsUsageTracker,
        OtpVerificationRepository otpVerificationRepository,
        RealtimeEventService realtimeEventService,
        Environment environment,
        ObjectProvider<BuildProperties> buildProperties,
        StorageMonitor storageMonitor
    ) {
        this.dataSource = dataSource;
        this.cacheManager = cacheManager;
        this.applicationProperties = applicationProperties;
        this.sendTextSmsProvider = sendTextSmsProvider;
        this.smsUsageTracker = smsUsageTracker;
        this.otpVerificationRepository = otpVerificationRepository;
        this.realtimeEventService = realtimeEventService;
        this.environment = environment;
        this.buildProperties = buildProperties;
        this.storageMonitor = storageMonitor;
    }

    public MonitoringSnapshot snapshot() {
        List<String> warnings = new ArrayList<>();
        SystemInfo system = systemInfo();
        SmsInfo sms = smsInfo(warnings);
        StorageInfo storage = storageMonitor.storageInfo();

        List<ComponentStatus> components = List.of(
            checkDatabase(),
            checkRedis(),
            diskStatus(system, warnings),
            storageMonitor.status(storage, warnings),
            smsStatus(sms)
        );
        components
            .stream()
            .filter(c -> DOWN.equals(c.status()))
            .forEach(c -> warnings.add(0, c.label() + " : " + c.detail()));

        return new MonitoringSnapshot(
            overallStatus(components),
            Instant.now(),
            applicationInfo(),
            components,
            system,
            databasePool(),
            sms,
            storage,
            warnings
        );
    }

    // ── Composants ──────────────────────────────────────────────

    private ComponentStatus checkDatabase() {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            long latency = elapsedMs(start);
            return valid
                ? new ComponentStatus("database", "Base de données", UP, "PostgreSQL répond en " + formatLatency(latency), latency)
                : new ComponentStatus("database", "Base de données", DOWN, "connexion invalide", latency);
        } catch (Exception e) {
            return new ComponentStatus("database", "Base de données", DOWN, "injoignable (" + e.getClass().getSimpleName() + ")", null);
        }
    }

    /** Lecture réelle dans le cache (Redis via Redisson), bornée à 3 s pour ne jamais bloquer la page. */
    private ComponentStatus checkRedis() {
        Cache cache = cacheManager.getCache(UserRepository.USERS_BY_LOGIN_CACHE);
        if (cache == null) {
            return new ComponentStatus("redis", "Cache Redis", WARN, "cache non configuré", null);
        }
        long start = System.nanoTime();
        try {
            CompletableFuture.runAsync(() -> cache.get(REDIS_PING_KEY)).get(3, TimeUnit.SECONDS);
            long latency = elapsedMs(start);
            return new ComponentStatus("redis", "Cache Redis", UP, "Redis répond en " + formatLatency(latency), latency);
        } catch (TimeoutException e) {
            return new ComponentStatus("redis", "Cache Redis", DOWN, "aucune réponse en 3 s", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ComponentStatus("redis", "Cache Redis", DOWN, "vérification interrompue", null);
        } catch (Exception e) {
            return new ComponentStatus("redis", "Cache Redis", DOWN, "injoignable", null);
        }
    }

    private ComponentStatus diskStatus(SystemInfo system, List<String> warnings) {
        if (system.diskTotalGb() <= 0) {
            return new ComponentStatus("disk", "Espace disque", WARN, "taille du disque inconnue", null);
        }
        String detail = "%.1f Go libres sur %.1f Go".formatted(system.diskFreeGb(), system.diskTotalGb());
        if (system.diskFreeGb() / system.diskTotalGb() < LOW_DISK_RATIO) {
            warnings.add("Disque presque plein : " + detail + ".");
            return new ComponentStatus("disk", "Espace disque", WARN, detail, null);
        }
        return new ComponentStatus("disk", "Espace disque", UP, detail, null);
    }

    private ComponentStatus smsStatus(SmsInfo sms) {
        if (!sms.live()) {
            return new ComponentStatus("sms", "Envoi de SMS", INFO, "mode simulation : aucun SMS réel n'est envoyé", null);
        }
        if (sms.balanceError() != null) {
            return new ComponentStatus("sms", "Envoi de SMS", DOWN, "SendText " + sms.balanceError(), null);
        }
        boolean lowBalance = sms.remainingSms() != null && sms.remainingSms() < LOW_SMS_BALANCE;
        boolean expiring = sms.daysUntilExpiry() != null && sms.daysUntilExpiry() <= EXPIRY_WARNING_DAYS;
        boolean failing = sms.lastFailureAt() != null && (sms.lastSentAt() == null || sms.lastFailureAt().isAfter(sms.lastSentAt()));
        String detail = sms.remainingSms() != null ? sms.remainingSms() + " SMS restants chez SendText" : "SendText connecté";
        return new ComponentStatus("sms", "Envoi de SMS", lowBalance || expiring || failing ? WARN : UP, detail, null);
    }

    private static String overallStatus(List<ComponentStatus> components) {
        boolean databaseDown = components.stream().anyMatch(c -> "database".equals(c.key()) && DOWN.equals(c.status()));
        if (databaseDown) {
            return DOWN;
        }
        boolean degraded = components.stream().anyMatch(c -> DOWN.equals(c.status()) || WARN.equals(c.status()));
        return degraded ? WARN : UP;
    }

    // ── SMS et abonnement SendText ─────────────────────────────

    private SmsInfo smsInfo(List<String> warnings) {
        ApplicationProperties.Sms config = applicationProperties.getSms();
        boolean live = SendTextSmsProvider.PROVIDER_NAME.equalsIgnoreCase(config.getProvider());
        SendTextSmsProvider.Balance balance = live ? currentBalance() : null;

        Instant startOfToday = LocalDate.now(BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant now = Instant.now();
        SmsUsageTracker.Snapshot usage = smsUsageTracker.snapshot();

        Long remaining = balance != null ? balance.remainingSms() : null;
        Long daysUntilExpiry = balance != null ? daysUntil(balance.expiresAt()) : null;
        if (remaining != null && remaining < LOW_SMS_BALANCE) {
            warnings.add("Solde SMS faible : " + remaining + " SMS restants. Rechargez le compte SendText.");
        }
        if (daysUntilExpiry != null && daysUntilExpiry <= EXPIRY_WARNING_DAYS) {
            warnings.add(
                daysUntilExpiry < 0 ? "Le forfait SendText a expiré." : "Le forfait SendText expire dans " + daysUntilExpiry + " jour(s)."
            );
        }

        return new SmsInfo(
            config.getProvider(),
            live,
            config.getSenderName(),
            remaining,
            balance != null ? balance.expiresAt() : null,
            daysUntilExpiry,
            balance != null ? balance.error() : null,
            otpVerificationRepository.countByCreatedDateAfter(startOfToday),
            otpVerificationRepository.countByCreatedDateAfter(now.minus(7, ChronoUnit.DAYS)),
            otpVerificationRepository.countByCreatedDateAfter(now.minus(30, ChronoUnit.DAYS)),
            usage.sent(),
            usage.failed(),
            usage.lastSentAt(),
            usage.lastFailureAt()
        );
    }

    private SendTextSmsProvider.Balance currentBalance() {
        CachedBalance cached = cachedBalance;
        if (cached == null || cached.fetchedAt().plus(BALANCE_CACHE).isBefore(Instant.now())) {
            cached = new CachedBalance(sendTextSmsProvider.fetchBalance(), Instant.now());
            cachedBalance = cached;
        }
        return cached.balance();
    }

    /** SendText renvoie une date (« 2026-12-31 » ou un horodatage ISO) : seuls les 10 premiers caractères comptent. */
    static Long daysUntil(String expiresAt) {
        if (expiresAt == null || expiresAt.length() < 10) {
            return null;
        }
        try {
            return ChronoUnit.DAYS.between(LocalDate.now(BUSINESS_ZONE), LocalDate.parse(expiresAt.substring(0, 10)));
        } catch (Exception e) {
            return null;
        }
    }

    // ── Serveur ─────────────────────────────────────────────────

    private ApplicationInfo applicationInfo() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        BuildProperties build = buildProperties.getIfAvailable();
        return new ApplicationInfo(
            build != null ? build.getVersion() : "inconnue",
            Instant.ofEpochMilli(runtime.getStartTime()),
            runtime.getUptime() / 1000,
            Runtime.version().toString(),
            Arrays.asList(environment.getActiveProfiles()),
            realtimeEventService.connectedClients()
        );
    }

    private SystemInfo systemInfo() {
        Runtime jvm = Runtime.getRuntime();
        Double processCpu = null;
        Double systemCpu = null;
        long memoryTotal = 0;
        long memoryFree = 0;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            processCpu = percent(os.getProcessCpuLoad());
            systemCpu = percent(os.getCpuLoad());
            memoryTotal = os.getTotalMemorySize();
            memoryFree = os.getFreeMemorySize();
        }
        File disk = new File(".").getAbsoluteFile();
        return new SystemInfo(
            processCpu,
            systemCpu,
            jvm.availableProcessors(),
            toMb(jvm.totalMemory() - jvm.freeMemory()),
            toMb(jvm.maxMemory()),
            toMb(memoryTotal - memoryFree),
            toMb(memoryTotal),
            toGb(disk.getUsableSpace()),
            toGb(disk.getTotalSpace())
        );
    }

    private DatabasePool databasePool() {
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
                HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
                if (pool != null) {
                    return new DatabasePool(
                        pool.getActiveConnections(),
                        pool.getIdleConnections(),
                        hikari.getMaximumPoolSize(),
                        pool.getThreadsAwaitingConnection()
                    );
                }
            }
        } catch (Exception e) {
            // Pool indisponible : la carte affichera « inconnu »
        }
        return null;
    }

    private static Double percent(double ratio) {
        return ratio < 0 ? null : Math.round(ratio * 1000) / 10.0;
    }

    private static long toMb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static double toGb(long bytes) {
        return Math.round((bytes / (1024.0 * 1024 * 1024)) * 10) / 10.0;
    }

    private static String formatLatency(long latencyMs) {
        return latencyMs < 1 ? "moins d'1 ms" : latencyMs + " ms";
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private record CachedBalance(SendTextSmsProvider.Balance balance, Instant fetchedAt) {}

    // ── Réponse de GET /api/admin/monitoring ────────────────────

    public record MonitoringSnapshot(
        String status,
        Instant checkedAt,
        ApplicationInfo application,
        List<ComponentStatus> components,
        SystemInfo system,
        DatabasePool database,
        SmsInfo sms,
        StorageInfo storage,
        List<String> warnings
    ) {}

    public record ComponentStatus(String key, String label, String status, String detail, Long latencyMs) {}

    public record ApplicationInfo(
        String version,
        Instant startedAt,
        long uptimeSeconds,
        String javaVersion,
        List<String> profiles,
        int realtimeClients
    ) {}

    public record SystemInfo(
        Double processCpuPercent,
        Double systemCpuPercent,
        int processors,
        long heapUsedMb,
        long heapMaxMb,
        long memoryUsedMb,
        long memoryTotalMb,
        double diskFreeGb,
        double diskTotalGb
    ) {}

    /** Stockage des images : provider actif, compte Cloudinary (si clés présentes) et dossier local. */
    public record StorageInfo(
        String provider,
        boolean cloudinaryConfigured,
        String cloudName,
        CloudinaryUsage cloudinary,
        String cloudinaryError,
        boolean localWritable,
        long localFiles,
        double localSizeMb,
        long cloudinaryUploadsSinceStartup,
        long cloudinaryFailuresSinceStartup,
        long localUploadsSinceStartup,
        Instant lastCloudinaryUploadAt,
        Instant lastCloudinaryFailureAt,
        /** Nombre de fichiers sur Cloudinary à l'instant (API Search), null si indisponible. */
        Long cloudinaryLiveResources
    ) {}

    /** Consommation Cloudinary sur la période en cours (stockage et bande passante en octets). */
    public record CloudinaryUsage(
        String plan,
        Double creditsUsed,
        Double creditsLimit,
        Double creditsUsedPercent,
        Long storageBytes,
        Long bandwidthBytes,
        Long transformations,
        Long resources,
        String lastUpdated,
        Long latencyMs
    ) {}

    public record DatabasePool(int activeConnections, int idleConnections, int maxConnections, int waitingThreads) {}

    public record SmsInfo(
        String provider,
        boolean live,
        String senderName,
        Long remainingSms,
        String expiresAt,
        Long daysUntilExpiry,
        String balanceError,
        long otpToday,
        long otpLast7Days,
        long otpLast30Days,
        long sentSinceStartup,
        long failedSinceStartup,
        Instant lastSentAt,
        Instant lastFailureAt
    ) {}
}
