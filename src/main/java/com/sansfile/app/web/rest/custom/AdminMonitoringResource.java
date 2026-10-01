package com.sansfile.app.web.rest.custom;

import com.sansfile.app.service.custom.monitoring.MonitoringService;
import com.sansfile.app.service.custom.monitoring.MonitoringService.MonitoringSnapshot;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Supervision de la plateforme pour la console admin (réservé aux administrateurs : règle /api/** de SecurityConfiguration).
 */
@Tag(name = "8. Administration & Statistiques", description = "KPIs et métriques de la plateforme SansFile")
@RestController
@RequestMapping("/api/admin")
public class AdminMonitoringResource {

    private final MonitoringService monitoringService;

    public AdminMonitoringResource(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    /**
     * GET /api/admin/monitoring : santé des composants, ressources du serveur et abonnement SMS.
     */
    @GetMapping("/monitoring")
    public ResponseEntity<MonitoringSnapshot> getMonitoring() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(monitoringService.snapshot());
    }
}
