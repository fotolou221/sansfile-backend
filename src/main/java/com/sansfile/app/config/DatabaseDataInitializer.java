package com.sansfile.app.config;

import com.sansfile.app.domain.*;
import com.sansfile.app.repository.*;
import com.sansfile.app.security.AuthoritiesConstants;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initialisateur de démarrage SansFile : garantit l'existence des rôles,
 * du super-administrateur unique et des paramètres de la plateforme.
 * Aucune donnée métier (catégories, salons, produits…) n'est créée : la base démarre vide.
 */
@Component
public class DatabaseDataInitializer implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseDataInitializer.class);

    private final PlatformSettingsRepository platformSettingsRepository;
    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationProperties applicationProperties;
    private final org.springframework.core.env.Environment environment;

    /** Identifiant de l'admin créé par Liquibase (changeset 20260912000000-2) et valeur par défaut d'ADMIN_EMAIL. */
    private static final String LEGACY_ADMIN_LOGIN = "admin@sansfile.sn";

    /** Mot de passe admin par défaut, accepté uniquement hors production. */
    private static final String DEV_ADMIN_PASSWORD = "admin_sansfile_2026";
    private static final int MIN_PROD_ADMIN_PASSWORD_LENGTH = 12;

    public DatabaseDataInitializer(
        PlatformSettingsRepository platformSettingsRepository,
        UserRepository userRepository,
        AuthorityRepository authorityRepository,
        PasswordEncoder passwordEncoder,
        ApplicationProperties applicationProperties,
        org.springframework.core.env.Environment environment
    ) {
        this.applicationProperties = applicationProperties;
        this.environment = environment;
        this.platformSettingsRepository = platformSettingsRepository;
        this.userRepository = userRepository;
        this.authorityRepository = authorityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        initAuthorities();
        initAdminUser();
        initPlatformSettings();
        LOG.info("🚀 SansFile initialisé en mode propre (Prêt pour la production).");
    }

    private void initAuthorities() {
        List<String> roles = List.of(
            AuthoritiesConstants.ADMIN,
            AuthoritiesConstants.USER,
            AuthoritiesConstants.CLIENT,
            AuthoritiesConstants.COIFFEUR,
            AuthoritiesConstants.SUPER_ADMIN,
            AuthoritiesConstants.AGENT
        );
        for (String r : roles) {
            if (!authorityRepository.existsById(r)) {
                Authority auth = new Authority();
                auth.setName(r);
                authorityRepository.save(auth);
            }
        }
    }

    private void initAdminUser() {
        String login = resolveAdminLogin();
        // L'admin historique (créé par Liquibase sous admin@sansfile.sn) est renommé, jamais dupliqué
        Optional<User> optAdmin = userRepository
            .findOneByLogin(login)
            .or(() -> userRepository.findOneByLogin(LEGACY_ADMIN_LOGIN))
            .or(() -> userRepository.findOneByLogin("admin"))
            .or(() -> userRepository.findOneByPhone("+221778627052"));

        User admin = optAdmin.orElseGet(User::new);
        admin.setLogin(login);
        admin.setEmail(LEGACY_ADMIN_LOGIN.equals(login) ? "contact@sansfile.sn" : login);
        admin.setPhone("+221778627052");
        String password = resolveAdminPassword();
        if (admin.getPassword() == null || !passwordEncoder.matches(password, admin.getPassword())) {
            admin.setPassword(passwordEncoder.encode(password));
        }
        admin.setFirstName("Super");
        admin.setLastName("Admin");
        admin.setActivated(true);
        admin.setLangKey("fr");

        Set<Authority> authorities = new HashSet<>();
        authorityRepository.findById(AuthoritiesConstants.ADMIN).ifPresent(authorities::add);
        authorityRepository.findById(AuthoritiesConstants.SUPER_ADMIN).ifPresent(authorities::add);
        authorityRepository.findById(AuthoritiesConstants.USER).ifPresent(authorities::add);
        admin.setAuthorities(authorities);
        userRepository.save(admin);
        LOG.info("👤 Compte super-administrateur '{}' synchronisé.", login);
    }

    /** ADMIN_EMAIL : identifiant de connexion de l'admin, admin@sansfile.sn par défaut. */
    private String resolveAdminLogin() {
        String configured = applicationProperties.getAdmin().getEmail();
        if (configured == null || configured.isBlank()) {
            return LEGACY_ADMIN_LOGIN;
        }
        String login = configured.trim().toLowerCase(Locale.ENGLISH);
        if (!login.contains("@")) {
            throw new IllegalStateException("ADMIN_EMAIL invalide : une adresse e-mail est attendue (ex. admin@sansfile.com).");
        }
        return login;
    }

    /** ADMIN_PASSWORD est la source de vérité ; sans elle, la production refuse de démarrer. */
    private String resolveAdminPassword() {
        String configured = applicationProperties.getAdmin().getPassword();
        boolean production = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
        if (configured != null && !configured.isBlank()) {
            if (production && configured.length() < MIN_PROD_ADMIN_PASSWORD_LENGTH) {
                throw new IllegalStateException(
                    "ADMIN_PASSWORD trop court : " + MIN_PROD_ADMIN_PASSWORD_LENGTH + " caractères minimum en production."
                );
            }
            return configured;
        }
        if (production) {
            throw new IllegalStateException(
                "ADMIN_PASSWORD manquant : définissez le mot de passe du compte admin dans les variables d'environnement."
            );
        }
        return DEV_ADMIN_PASSWORD;
    }

    private void initPlatformSettings() {
        // Valeurs par défaut uniquement à la création : ensuite, l'admin les gère depuis la console
        if (platformSettingsRepository.count() > 0) {
            return;
        }
        PlatformSettings settings = new PlatformSettings();
        settings.setAppName("SansFile");
        settings.setContactEmail("contact@sansfile.com");
        settings.setContactPhone("+221 77 862 70 52");
        settings.setCommissionRate(10.0);
        settings.setOpeningTime("09:00");
        settings.setClosingTime("21:00");
        settings.setAllowRelativeBooking(true);
        settings.setMaintenanceMode(false);
        platformSettingsRepository.save(settings);
        LOG.info("⚙️ Paramètres de la plateforme SansFile créés avec les valeurs par défaut.");
    }
}
