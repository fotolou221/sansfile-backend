package com.sansfile.app.service.custom.agent;

import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.domain.Authority;
import com.sansfile.app.domain.CoiffeurProfile;
import com.sansfile.app.domain.Salon;
import com.sansfile.app.domain.User;
import com.sansfile.app.domain.enumeration.AgentAction;
import com.sansfile.app.repository.AuthorityRepository;
import com.sansfile.app.repository.CoiffeurProfileRepository;
import com.sansfile.app.repository.SalonRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.security.SecurityUtils;
import com.sansfile.app.service.custom.agent.AgentAccountException.Kind;
import com.sansfile.app.service.dto.SalonDTO;
import com.sansfile.app.service.mapper.SalonMapper;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comptes des agents de terrain : créés par l'admin avec un mot de passe provisoire, que l'agent doit
 * remplacer à sa première connexion. Toutes les actions sont écrites dans le journal des agents.
 */
@Service
@Transactional
public class AgentAccountService {

    public static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 100;
    /** La colonne « login » de jhi_user est limitée à 50 caractères ; l'e-mail sert d'identifiant. */
    private static final int MAX_EMAIL_LENGTH = 50;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final ZoneId DAKAR = ZoneId.of("Africa/Dakar");

    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final SalonRepository salonRepository;
    private final CoiffeurProfileRepository coiffeurProfileRepository;
    private final SalonMapper salonMapper;
    private final PasswordEncoder passwordEncoder;
    private final AgentActivityService activityService;
    private final ApplicationProperties applicationProperties;
    private final CacheManager cacheManager;

    public AgentAccountService(
        UserRepository userRepository,
        AuthorityRepository authorityRepository,
        SalonRepository salonRepository,
        CoiffeurProfileRepository coiffeurProfileRepository,
        SalonMapper salonMapper,
        PasswordEncoder passwordEncoder,
        AgentActivityService activityService,
        ApplicationProperties applicationProperties,
        CacheManager cacheManager
    ) {
        this.userRepository = userRepository;
        this.authorityRepository = authorityRepository;
        this.salonRepository = salonRepository;
        this.coiffeurProfileRepository = coiffeurProfileRepository;
        this.salonMapper = salonMapper;
        this.passwordEncoder = passwordEncoder;
        this.activityService = activityService;
        this.applicationProperties = applicationProperties;
        this.cacheManager = cacheManager;
    }

    /** Formulaire de l'admin (création ou modification). Le téléphone est facultatif. */
    public record AgentForm(String firstName, String lastName, String email, String phone) {}

    public record AgentSummary(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        boolean activated,
        boolean mustChangePassword,
        long salonsCount,
        Instant lastLoginAt,
        Instant createdDate
    ) {}

    /** Réponse à la création / réinitialisation : le mot de passe provisoire à transmettre à l'agent. */
    public record AgentCredentials(AgentSummary agent, String temporaryPassword) {}

    public record AgentProfile(Long id, String firstName, String lastName, String email, String phone, boolean mustChangePassword) {}

    public record AgentDashboard(long salonsTotal, long salonsThisMonth, long salonsThisWeek, List<SalonDTO> recentSalons) {}

    // ── Console d'administration ────────────────────────────────

    @Transactional(readOnly = true)
    public List<AgentSummary> listAgents() {
        Map<Long, Long> salonCounts = new HashMap<>();
        for (Object[] row : salonRepository.countSalonsByAgent()) {
            salonCounts.put((Long) row[0], (Long) row[1]);
        }
        Map<Long, Instant> lastLogins = activityService.lastDateByAgent(AgentAction.LOGIN);
        return userRepository
            .findAllByAuthorityName(AuthoritiesConstants.AGENT)
            .stream()
            .map(u -> summary(u, salonCounts.getOrDefault(u.getId(), 0L), lastLogins.get(u.getId())))
            .toList();
    }

    @Transactional(readOnly = true)
    public AgentSummary getAgent(Long agentId) {
        return summary(findAgent(agentId));
    }

    public AgentCredentials createAgent(AgentForm form) {
        String email = validEmail(form.email());
        String phone = normalizePhone(form.phone());
        assertEmailFree(email, null);
        assertPhoneFree(phone, null);

        User agent = new User();
        agent.setLogin(email);
        agent.setEmail(email);
        agent.setFirstName(requiredName(form.firstName(), "prénom"));
        agent.setLastName(requiredName(form.lastName(), "nom"));
        agent.setPhone(phone);
        agent.setLangKey("fr");
        agent.setActivated(true);
        agent.setMustChangePassword(true);
        agent.setPassword(passwordEncoder.encode(defaultPassword()));
        Set<Authority> authorities = new HashSet<>();
        authorityRepository.findById(AuthoritiesConstants.USER).ifPresent(authorities::add);
        authorities.add(
            authorityRepository
                .findById(AuthoritiesConstants.AGENT)
                .orElseThrow(() -> new IllegalStateException("Rôle ROLE_AGENT absent de la base."))
        );
        agent.setAuthorities(authorities);
        agent = userRepository.saveAndFlush(agent);

        activityService.record(agent.getId(), AgentAction.ACCOUNT_CREATED, "Compte créé par l'administration (" + email + ")", null);
        return new AgentCredentials(summary(agent), defaultPassword());
    }

    public AgentSummary updateAgent(Long agentId, AgentForm form) {
        User agent = findAgent(agentId);
        String previousLogin = agent.getLogin();
        String previousEmail = agent.getEmail();
        String email = validEmail(form.email());
        String phone = normalizePhone(form.phone());
        assertEmailFree(email, agent.getId());
        assertPhoneFree(phone, agent.getId());

        agent.setFirstName(requiredName(form.firstName(), "prénom"));
        agent.setLastName(requiredName(form.lastName(), "nom"));
        agent.setLogin(email);
        agent.setEmail(email);
        agent.setPhone(phone);
        agent = userRepository.saveAndFlush(agent);
        evictUserCaches(previousLogin, previousEmail);
        evictUserCaches(agent.getLogin(), agent.getEmail());

        String detail = previousLogin.equals(email)
            ? "Informations du compte modifiées par l'administration"
            : "Informations modifiées par l'administration (identifiant : " + previousLogin + " → " + email + ")";
        activityService.record(agent.getId(), AgentAction.ACCOUNT_UPDATED, detail, null);
        return summary(agent);
    }

    /** Remet le mot de passe provisoire : l'agent devra en choisir un nouveau à sa prochaine connexion. */
    public AgentCredentials resetPassword(Long agentId) {
        User agent = findAgent(agentId);
        agent.setPassword(passwordEncoder.encode(defaultPassword()));
        agent.setMustChangePassword(true);
        userRepository.saveAndFlush(agent);
        evictUserCaches(agent.getLogin(), agent.getEmail());
        activityService.record(agent.getId(), AgentAction.PASSWORD_RESET, "Mot de passe réinitialisé par l'administration", null);
        return new AgentCredentials(summary(agent), defaultPassword());
    }

    /** Un agent désactivé ne peut plus se connecter ; ses actions passées restent dans le journal. */
    public AgentSummary setActivated(Long agentId, boolean activated) {
        User agent = findAgent(agentId);
        if (agent.isActivated() != activated) {
            agent.setActivated(activated);
            userRepository.saveAndFlush(agent);
            evictUserCaches(agent.getLogin(), agent.getEmail());
            activityService.record(
                agent.getId(),
                activated ? AgentAction.ACCOUNT_ENABLED : AgentAction.ACCOUNT_DISABLED,
                activated ? "Compte réactivé par l'administration" : "Compte désactivé par l'administration",
                null
            );
        }
        return summary(agent);
    }

    @Transactional(readOnly = true)
    public List<SalonDTO> salonsOf(Long agentId) {
        findAgent(agentId);
        return salonsCreatedBy(agentId);
    }

    // ── Espace agent ────────────────────────────────────────────

    /** Profil de l'agent connecté (accessible même avant le changement du mot de passe provisoire). */
    @Transactional(readOnly = true)
    public AgentProfile currentProfile() {
        User agent = currentAgent();
        return new AgentProfile(
            agent.getId(),
            agent.getFirstName(),
            agent.getLastName(),
            agent.getEmail(),
            agent.getPhone(),
            agent.isMustChangePassword()
        );
    }

    /**
     * Agent connecté, actif et dont le mot de passe n'est plus le provisoire : condition de toute action
     * (inscrire ou modifier un salon, consulter ses statistiques).
     */
    @Transactional(readOnly = true)
    public User requireActiveAgent() {
        User agent = currentAgent();
        if (agent.isMustChangePassword()) {
            throw AgentAccountException.passwordChangeRequired();
        }
        return agent;
    }

    public void changePassword(String currentPassword, String newPassword) {
        User agent = currentAgent();
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, agent.getPassword())) {
            throw new AgentAccountException(Kind.INVALID, "wrong-current-password", "Le mot de passe actuel est incorrect.");
        }
        assertStrongPassword(newPassword, currentPassword);
        agent.setPassword(passwordEncoder.encode(newPassword));
        agent.setMustChangePassword(false);
        userRepository.saveAndFlush(agent);
        evictUserCaches(agent.getLogin(), agent.getEmail());
        activityService.record(agent.getId(), AgentAction.PASSWORD_CHANGED, "Mot de passe personnel défini", null);
    }

    public void recordLogout() {
        User agent = currentAgent();
        activityService.record(agent.getId(), AgentAction.LOGOUT, "Déconnexion", null);
    }

    @Transactional(readOnly = true)
    public AgentDashboard dashboard() {
        User agent = requireActiveAgent();
        LocalDate today = LocalDate.now(DAKAR);
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(DAKAR).toInstant();
        Instant weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(DAKAR).toInstant();
        List<SalonDTO> salons = salonsCreatedBy(agent.getId());
        return new AgentDashboard(
            salons.size(),
            activityService.countSince(agent.getId(), AgentAction.SALON_CREATED, monthStart),
            activityService.countSince(agent.getId(), AgentAction.SALON_CREATED, weekStart),
            salons.stream().limit(5).toList()
        );
    }

    @Transactional(readOnly = true)
    public List<SalonDTO> mySalons() {
        return salonsCreatedBy(requireActiveAgent().getId());
    }

    /** Pour le journal : l'utilisateur connecté est-il un agent de terrain (et pas un admin) ? */
    public static boolean isAgentOnly() {
        return (
            SecurityUtils.hasCurrentUserThisAuthority(AuthoritiesConstants.AGENT) &&
            !SecurityUtils.hasCurrentUserAnyOfAuthorities(AuthoritiesConstants.ADMIN, AuthoritiesConstants.SUPER_ADMIN)
        );
    }

    public static String displayName(User user) {
        String name = (
            (user.getFirstName() != null ? user.getFirstName() : "") +
            " " +
            (user.getLastName() != null ? user.getLastName() : "")
        ).trim();
        return name.isEmpty() ? user.getLogin() : name;
    }

    // ── Outils ──────────────────────────────────────────────────

    private User currentAgent() {
        String login = SecurityUtils.getCurrentUserLogin().orElseThrow(() ->
            new AgentAccountException(Kind.FORBIDDEN, "not-agent", "Connectez-vous avec votre compte agent.")
        );
        User user = userRepository
            .findOneByLogin(login)
            .filter(AgentAccountService::hasAgentRole)
            .orElseThrow(() -> new AgentAccountException(Kind.FORBIDDEN, "not-agent", "Ce compte n'est pas un compte agent de terrain."));
        if (!user.isActivated()) {
            throw new AgentAccountException(Kind.FORBIDDEN, "agent-disabled", "Ce compte agent est désactivé. Contactez l'administration.");
        }
        return user;
    }

    private User findAgent(Long agentId) {
        return userRepository
            .findById(agentId)
            .filter(AgentAccountService::hasAgentRole)
            .orElseThrow(() -> new AgentAccountException(Kind.NOT_FOUND, "agent-not-found", "Agent de terrain introuvable."));
    }

    private static boolean hasAgentRole(User user) {
        return user
            .getAuthorities()
            .stream()
            .anyMatch(a -> AuthoritiesConstants.AGENT.equals(a.getName()));
    }

    private List<SalonDTO> salonsCreatedBy(Long agentId) {
        return salonRepository.findAllByCreatedByAgentIdOrderByIdDesc(agentId).stream().map(this::toDtoWithOwner).toList();
    }

    private SalonDTO toDtoWithOwner(Salon salon) {
        SalonDTO dto = salonMapper.toDto(salon);
        coiffeurProfileRepository
            .findBySalonId(salon.getId())
            .stream()
            .map(CoiffeurProfile::getName)
            .filter(name -> name != null && !name.isBlank())
            .findFirst()
            .ifPresent(name -> {
                dto.setOwnerName(name);
                dto.setCoiffeurName(name);
            });
        return dto;
    }

    private AgentSummary summary(User agent) {
        Instant lastLogin = activityService.lastDateByAgent(AgentAction.LOGIN).get(agent.getId());
        return summary(agent, salonRepository.countByCreatedByAgentId(agent.getId()), lastLogin);
    }

    private static AgentSummary summary(User agent, long salonsCount, Instant lastLoginAt) {
        return new AgentSummary(
            agent.getId(),
            agent.getFirstName(),
            agent.getLastName(),
            agent.getEmail(),
            agent.getPhone(),
            agent.isActivated(),
            agent.isMustChangePassword(),
            salonsCount,
            lastLoginAt,
            agent.getCreatedDate()
        );
    }

    private String defaultPassword() {
        return applicationProperties.getAgent().getDefaultPassword();
    }

    private void assertStrongPassword(String newPassword, String currentPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH || newPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new AgentAccountException(
                Kind.INVALID,
                "weak-password",
                "Le nouveau mot de passe doit contenir au moins " + MIN_PASSWORD_LENGTH + " caractères."
            );
        }
        if (!newPassword.matches(".*\\p{L}.*") || !newPassword.matches(".*\\d.*")) {
            throw new AgentAccountException(
                Kind.INVALID,
                "weak-password",
                "Le nouveau mot de passe doit contenir au moins une lettre et un chiffre."
            );
        }
        if (newPassword.equals(defaultPassword()) || newPassword.equals(currentPassword)) {
            throw new AgentAccountException(
                Kind.INVALID,
                "same-password",
                "Choisissez un mot de passe différent du mot de passe provisoire."
            );
        }
    }

    private static String validEmail(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches()) {
            throw new AgentAccountException(Kind.INVALID, "invalid-email", "Adresse e-mail invalide.");
        }
        if (email.length() > MAX_EMAIL_LENGTH) {
            throw new AgentAccountException(
                Kind.INVALID,
                "invalid-email",
                "L'adresse e-mail ne doit pas dépasser " + MAX_EMAIL_LENGTH + " caractères."
            );
        }
        return email;
    }

    private static String requiredName(String raw, String label) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 50) {
            throw new AgentAccountException(
                Kind.INVALID,
                "invalid-name",
                "Renseignez le " + label + " de l'agent (50 caractères maximum)."
            );
        }
        return value;
    }

    /** Format des comptes SansFile (+221XXXXXXXXX) ; vide si non renseigné. */
    static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 9) {
            return "+221" + digits;
        }
        if (digits.length() == 12 && digits.startsWith("221")) {
            return "+" + digits;
        }
        if (digits.length() < 9 || digits.length() > 15) {
            throw new AgentAccountException(Kind.INVALID, "invalid-phone", "Numéro de téléphone invalide.");
        }
        return "+" + digits;
    }

    private void assertEmailFree(String email, Long agentId) {
        Optional<User> byLogin = userRepository.findOneByLogin(email);
        Optional<User> byEmail = userRepository.findOneByEmailIgnoreCase(email);
        boolean taken = byLogin
            .or(() -> byEmail)
            .filter(u -> !u.getId().equals(agentId))
            .isPresent();
        if (taken) {
            throw new AgentAccountException(Kind.CONFLICT, "email-used", "Cette adresse e-mail est déjà utilisée par un autre compte.");
        }
    }

    private void assertPhoneFree(String phone, Long agentId) {
        if (phone == null) {
            return;
        }
        boolean taken = userRepository
            .findOneByPhone(phone)
            .filter(u -> !u.getId().equals(agentId))
            .isPresent();
        if (taken) {
            throw new AgentAccountException(
                Kind.CONFLICT,
                "phone-used",
                "Ce numéro est déjà utilisé par un autre compte (client ou coiffeur). Laissez le champ vide ou utilisez un autre numéro."
            );
        }
    }

    /** Les comptes sont mis en cache à la connexion : sans cela, l'ancien mot de passe resterait valable. */
    private void evictUserCaches(String login, String email) {
        Cache byLogin = cacheManager.getCache(UserRepository.USERS_BY_LOGIN_CACHE);
        if (byLogin != null && login != null) {
            byLogin.evictIfPresent(login);
        }
        Cache byEmail = cacheManager.getCache(UserRepository.USERS_BY_EMAIL_CACHE);
        if (byEmail != null && email != null) {
            byEmail.evictIfPresent(email);
        }
    }
}
