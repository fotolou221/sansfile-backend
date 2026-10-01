package com.sansfile.app.config;

import static org.springframework.security.config.Customizer.withDefaults;

import com.sansfile.app.security.*;
import com.sansfile.app.service.custom.maintenance.MaintenanceModeService;
import com.sansfile.app.web.filter.MaintenanceModeFilter;
import com.sansfile.app.web.filter.RateLimitingFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tech.jhipster.config.JHipsterProperties;

@Configuration
@EnableMethodSecurity(securedEnabled = true)
public class SecurityConfiguration {

    private final JHipsterProperties jHipsterProperties;

    public SecurityConfiguration(JHipsterProperties jHipsterProperties) {
        this.jHipsterProperties = jHipsterProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectProvider<MaintenanceModeService> maintenanceModeService) {
        http.cors(withDefaults())
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authz ->
                authz
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/error")
                    .permitAll()
                    // ── Authentification (mot de passe réservé aux admins, SMS pour les autres) ──
                    .requestMatchers("/api/authenticate")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/otp/send", "/api/auth/otp/verify", "/api/auth/refresh")
                    .permitAll()
                    // Inscription et réinitialisation par e-mail de JHipster : inutilisées (connexion par SMS)
                    .requestMatchers("/api/register", "/api/activate", "/api/account/reset-password/**")
                    .denyAll()
                    // ── Lecture publique ──
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/public/**",
                        "/api/vitrine/**",
                        "/api/platform-settings",
                        "/api/platform-settings/**"
                    )
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/products",
                        "/api/products/**",
                        "/api/product-categories",
                        "/api/product-categories/**",
                        "/api/categories",
                        "/api/categories/**"
                    )
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/salons/*/queue")
                    .hasAnyAuthority(AuthoritiesConstants.ADMIN, AuthoritiesConstants.COIFFEUR)
                    .requestMatchers(HttpMethod.GET, "/api/salons", "/api/salons/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/files/**", "/api/realtime/events", "/api/push/public-key")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/uptime", "/api/uptime/**", "/api/health", "/api/health/**")
                    .permitAll()
                    .requestMatchers("/management/health", "/management/health/**", "/management/info")
                    .permitAll()
                    // ── Espace coiffeur (le contrôleur vérifie que le salon est bien le sien) ──
                    .requestMatchers(HttpMethod.POST, "/api/tickets/walk-in", "/api/tickets/*/call-next", "/api/tickets/*/serve")
                    .hasAnyAuthority(AuthoritiesConstants.ADMIN, AuthoritiesConstants.COIFFEUR)
                    .requestMatchers(HttpMethod.PUT, "/api/salons/*/toggle-status")
                    .hasAnyAuthority(AuthoritiesConstants.ADMIN, AuthoritiesConstants.COIFFEUR)
                    .requestMatchers(HttpMethod.PATCH, "/api/salons/*", "/api/salons/*/toggle-status")
                    .hasAnyAuthority(AuthoritiesConstants.ADMIN, AuthoritiesConstants.COIFFEUR)
                    // Téléversement : tout compte connecté (image vérifiée, dossier « avatars » imposé aux clients)
                    .requestMatchers(HttpMethod.POST, "/api/storage/upload", "/api/files/upload")
                    .authenticated()
                    // ── Utilisateur connecté (le contrôleur vérifie le propriétaire) ──
                    .requestMatchers(HttpMethod.GET, "/api/tickets/count")
                    .hasAuthority(AuthoritiesConstants.ADMIN)
                    .requestMatchers(HttpMethod.POST, "/api/tickets/book-multiple", "/api/tickets/*/cancel")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/tickets/my-tickets", "/api/tickets/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/orders/checkout")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/orders/my-orders")
                    .authenticated()
                    .requestMatchers(
                        "/api/favorites/**",
                        "/api/relatives",
                        "/api/relatives/**",
                        "/api/push/subscriptions",
                        "/api/account",
                        "/api/account/**"
                    )
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/notifications",
                        "/api/app-notifications",
                        "/api/notifications/my-notifications",
                        "/api/app-notifications/my-notifications"
                    )
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/notifications/*/read", "/api/app-notifications/*/read")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/notifications/mark-all-read", "/api/app-notifications/mark-all-read")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/notifications/*", "/api/app-notifications/*")
                    .authenticated()
                    // ── Documentation et supervision ──
                    .requestMatchers("/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/management/**")
                    .hasAuthority(AuthoritiesConstants.ADMIN)
                    // ── Tout le reste de l'API (administration, écritures génériques) : admin uniquement ──
                    .requestMatchers("/api/**")
                    .hasAuthority(AuthoritiesConstants.ADMIN)
                    .anyRequest()
                    .denyAll()
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions ->
                exceptions
                    .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                    .accessDeniedHandler(new BearerTokenAccessDeniedHandler())
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()))
            // Anti force brute / anti envoi massif de SMS, avant même la lecture du jeton
            .addFilterBefore(new RateLimitingFilter(), BearerTokenAuthenticationFilter.class)
            // Mode maintenance, une fois le jeton lu (les administrateurs passent)
            .addFilterAfter(new MaintenanceModeFilter(maintenanceModeService), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
