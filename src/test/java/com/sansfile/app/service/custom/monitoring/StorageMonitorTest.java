package com.sansfile.app.service.custom.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudinary.Cloudinary;
import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.service.custom.monitoring.MonitoringService.CloudinaryUsage;
import com.sansfile.app.service.custom.monitoring.MonitoringService.ComponentStatus;
import com.sansfile.app.service.custom.monitoring.MonitoringService.StorageInfo;
import com.sansfile.app.service.custom.storage.StorageUsageTracker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StorageMonitorTest {

    private final StorageMonitor monitor = new StorageMonitor(new Cloudinary(), new ApplicationProperties(), new StorageUsageTracker());

    @Test
    void readsCreditBasedUsageResponse() {
        // Format réel renvoyé par GET /usage pour un forfait Free
        Map<String, Object> response = Map.of(
            "plan",
            "Free",
            "last_updated",
            "2026-09-30",
            "transformations",
            Map.of("usage", 35, "credits_usage", 0.04),
            "bandwidth",
            Map.of("usage", 80962501, "credits_usage", 0.08),
            "storage",
            Map.of("usage", 193918646, "credits_usage", 0.18),
            "credits",
            Map.of("usage", 0.3, "limit", 25, "used_percent", 1.2),
            "resources",
            99
        );

        CloudinaryUsage usage = StorageMonitor.toUsage(response, 120);

        assertThat(usage.plan()).isEqualTo("Free");
        assertThat(usage.creditsUsed()).isEqualTo(0.3);
        assertThat(usage.creditsLimit()).isEqualTo(25.0);
        assertThat(usage.creditsUsedPercent()).isEqualTo(1.2);
        assertThat(usage.storageBytes()).isEqualTo(193918646L);
        assertThat(usage.bandwidthBytes()).isEqualTo(80962501L);
        assertThat(usage.transformations()).isEqualTo(35L);
        assertThat(usage.resources()).isEqualTo(99L);
        assertThat(usage.lastUpdated()).isEqualTo("2026-09-30");
        assertThat(usage.latencyMs()).isEqualTo(120L);
    }

    @Test
    void legacyPlansUseTheHighestResourcePercentage() {
        Map<String, Object> response = Map.of(
            "plan",
            "Plus",
            "storage",
            Map.of("usage", 1000, "limit", 2000, "used_percent", 50.0),
            "bandwidth",
            Map.of("usage", 1700, "limit", 2000, "used_percent", 85.0)
        );

        CloudinaryUsage usage = StorageMonitor.toUsage(response, 10);

        assertThat(usage.creditsUsed()).isNull();
        assertThat(usage.creditsUsedPercent()).isEqualTo(85.0);
        assertThat(usage.resources()).isNull();
    }

    @Test
    void readsLiveResourceCountFromSearchResponse() {
        // Extrait de POST /resources/search sans critère (max_results=1)
        Map<String, Object> response = Map.of("total_count", 42, "time", 15, "resources", List.of(Map.of("public_id", "sansfile/a")));

        assertThat(StorageMonitor.toTotalCount(response)).isEqualTo(42L);
        assertThat(StorageMonitor.toTotalCount(Map.of("error", Map.of("message", "x")))).isNull();
    }

    @Test
    void localStorageIsUpWhenWritable() {
        List<String> warnings = new ArrayList<>();
        ComponentStatus status = monitor.status(local(true), warnings);

        assertThat(status.status()).isEqualTo(MonitoringService.UP);
        assertThat(status.detail()).contains("12 image(s)");
        assertThat(warnings).isEmpty();
    }

    @Test
    void localStorageIsDownWhenNotWritable() {
        ComponentStatus status = monitor.status(local(false), new ArrayList<>());

        assertThat(status.status()).isEqualTo(MonitoringService.DOWN);
    }

    @Test
    void cloudinaryWithoutKeysWarnsAboutLocalFallback() {
        List<String> warnings = new ArrayList<>();
        StorageInfo storage = cloudinary(false, null, null, null, null);

        assertThat(monitor.status(storage, warnings).status()).isEqualTo(MonitoringService.WARN);
        assertThat(warnings).singleElement().asString().contains("clés sont absentes");
    }

    @Test
    void unreachableCloudinaryWarns() {
        List<String> warnings = new ArrayList<>();
        StorageInfo storage = cloudinary(true, null, "injoignable (ConnectException)", null, null);

        assertThat(monitor.status(storage, warnings).status()).isEqualTo(MonitoringService.WARN);
        assertThat(warnings).singleElement().asString().contains("injoignable");
    }

    @Test
    void quotaThresholds() {
        List<String> warnings = new ArrayList<>();
        assertThat(monitor.status(cloudinary(true, usage(85.0), null, null, null), warnings).status()).isEqualTo(MonitoringService.WARN);
        assertThat(warnings).singleElement().asString().contains("85 %");

        assertThat(monitor.status(cloudinary(true, usage(100.0), null, null, null), new ArrayList<>()).status()).isEqualTo(
            MonitoringService.DOWN
        );
    }

    @Test
    void lastFailedUploadWarnsUntilTheNextSuccess() {
        Instant earlier = Instant.parse("2026-10-01T08:00:00Z");
        Instant later = Instant.parse("2026-10-01T09:00:00Z");

        assertThat(monitor.status(cloudinary(true, usage(10.0), null, earlier, later), new ArrayList<>()).status()).isEqualTo(
            MonitoringService.WARN
        );
        assertThat(monitor.status(cloudinary(true, usage(10.0), null, later, earlier), new ArrayList<>()).status()).isEqualTo(
            MonitoringService.UP
        );
    }

    @Test
    void healthyCloudinaryReportsLatencyAndQuota() {
        ComponentStatus status = monitor.status(cloudinary(true, usage(1.2), null, null, null), new ArrayList<>());

        assertThat(status.status()).isEqualTo(MonitoringService.UP);
        assertThat(status.detail()).contains("120 ms").contains("1 % du quota");
    }

    private static StorageInfo local(boolean writable) {
        return new StorageInfo("local", false, null, null, null, writable, 12, 3.4, 0, 0, 12, null, null, null);
    }

    private static StorageInfo cloudinary(
        boolean configured,
        CloudinaryUsage usage,
        String error,
        Instant lastUploadAt,
        Instant lastFailureAt
    ) {
        return new StorageInfo(
            "cloudinary",
            configured,
            configured ? "demo" : null,
            usage,
            error,
            true,
            0,
            0,
            1,
            lastFailureAt != null ? 1 : 0,
            0,
            lastUploadAt,
            lastFailureAt,
            null
        );
    }

    private static CloudinaryUsage usage(double usedPercent) {
        return new CloudinaryUsage("Free", 0.3, 25.0, usedPercent, 1L, 1L, 1L, 1L, "2026-09-30", 120L);
    }
}
