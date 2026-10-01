package com.sansfile.app.web.filter;

import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.security.SecurityUtils;
import com.sansfile.app.service.custom.maintenance.MaintenanceModeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mode maintenance : toute écriture sur l'API (ticket, commande, connexion par SMS, profil…) est refusée en 503,
 * sauf pour les administrateurs, qui doivent pouvoir travailler et lever la maintenance.
 * Les lectures restent ouvertes : le site et l'application savent ainsi qu'ils doivent afficher la maintenance.
 */
public class MaintenanceModeFilter extends OncePerRequestFilter {

    public static final String MESSAGE = "SansFile est en maintenance. Merci de réessayer dans quelques instants.";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /** Connexion admin (pour lever la maintenance) et renouvellement de session (évite des déconnexions inutiles). */
    private static final Set<String> ALWAYS_OPEN = Set.of("/api/authenticate", "/api/auth/refresh");

    private final ObjectProvider<MaintenanceModeService> maintenanceModeService;

    public MaintenanceModeFilter(ObjectProvider<MaintenanceModeService> maintenanceModeService) {
        this.maintenanceModeService = maintenanceModeService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || READ_METHODS.contains(request.getMethod()) || ALWAYS_OPEN.contains(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        // Admin vérifié en premier : ses requêtes ne lisent jamais l'état de maintenance
        if (!isAdmin() && isMaintenanceActive()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "120");
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"" + MESSAGE + "\",\"maintenance\":true}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isAdmin() {
        return SecurityUtils.hasCurrentUserAnyOfAuthorities(AuthoritiesConstants.ADMIN, AuthoritiesConstants.SUPER_ADMIN);
    }

    private boolean isMaintenanceActive() {
        MaintenanceModeService service = maintenanceModeService.getIfAvailable();
        return service != null && service.isActive();
    }
}
