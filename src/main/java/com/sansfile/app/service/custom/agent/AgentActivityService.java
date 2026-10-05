package com.sansfile.app.service.custom.agent;

import com.sansfile.app.domain.AgentActivity;
import com.sansfile.app.domain.User;
import com.sansfile.app.domain.enumeration.AgentAction;
import com.sansfile.app.repository.AgentActivityRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Journal des agents de terrain : chaque action d'un agent (connexion, salon inscrit…) et chaque action
 * de l'admin sur un compte agent. Consulté depuis la console d'administration.
 */
@Service
public class AgentActivityService {

    private static final Logger LOG = LoggerFactory.getLogger(AgentActivityService.class);

    private final AgentActivityRepository activityRepository;
    private final UserRepository userRepository;
    /** Écriture hors de toute transaction de l'appelant (connexion, salon inscrit…). */
    private final TransactionTemplate ownTransaction;

    public AgentActivityService(
        AgentActivityRepository activityRepository,
        UserRepository userRepository,
        PlatformTransactionManager transactionManager
    ) {
        this.activityRepository = activityRepository;
        this.userRepository = userRepository;
        this.ownTransaction = new TransactionTemplate(transactionManager);
    }

    public record ActivityView(
        Long id,
        Long agentId,
        String agentName,
        String agentEmail,
        AgentAction action,
        String description,
        Long salonId,
        String actor,
        String ipAddress,
        String userAgent,
        Instant createdDate
    ) {}

    /** Action de l'utilisateur connecté (agent ou admin). */
    public void record(Long agentId, AgentAction action, String description, Long salonId) {
        record(agentId, action, description, salonId, SecurityUtils.getCurrentUserLogin().orElse("système"));
    }

    /**
     * Écrit une ligne du journal.
     * <ul>
     *   <li>Appelé dans une transaction (création de compte, mot de passe…) : la ligne est enregistrée avec
     *   l'action, dans la même transaction. Une transaction séparée attendrait la ligne de l'agent, pas
     *   encore validée, et occuperait une seconde connexion à la base.</li>
     *   <li>Sinon (connexion, salon inscrit) : transaction propre ; un incident d'écriture est seulement
     *   tracé dans les logs du serveur, l'action de l'agent n'échoue pas.</li>
     * </ul>
     */
    public void record(Long agentId, AgentAction action, String description, Long salonId, String actor) {
        if (agentId == null || action == null) {
            return;
        }
        AgentActivity entry = new AgentActivity();
        entry.setAgentId(agentId);
        entry.setAction(action);
        entry.setDescription(truncate(description, 500));
        entry.setSalonId(salonId);
        entry.setActor(truncate(actor, 254));
        entry.setCreatedDate(Instant.now());
        HttpServletRequest request = currentRequest();
        if (request != null) {
            entry.setIpAddress(truncate(request.getRemoteAddr(), 64));
            entry.setUserAgent(truncate(request.getHeader("User-Agent"), 255));
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            activityRepository.save(entry);
            return;
        }
        try {
            ownTransaction.executeWithoutResult(status -> activityRepository.save(entry));
        } catch (RuntimeException e) {
            LOG.warn("Journal agent non écrit ({} pour l'agent {}) : {}", action, agentId, e.getMessage());
        }
    }

    public void recordLogin(Long agentId, String login) {
        record(agentId, AgentAction.LOGIN, "Connexion réussie", null, login);
    }

    /** Échec de connexion : tracé seulement si l'identifiant est celui d'un compte agent. */
    public void recordFailedLogin(String attemptedLogin, String reason) {
        if (attemptedLogin == null || attemptedLogin.isBlank()) {
            return;
        }
        String login = attemptedLogin.trim().toLowerCase(Locale.ROOT);
        userRepository
            .findOneWithAuthoritiesByLogin(login)
            .or(() -> userRepository.findOneWithAuthoritiesByEmailIgnoreCase(login))
            .filter(user ->
                user
                    .getAuthorities()
                    .stream()
                    .anyMatch(a -> AuthoritiesConstants.AGENT.equals(a.getName()))
            )
            .ifPresent(agent -> record(agent.getId(), AgentAction.LOGIN_FAILED, "Échec de connexion : " + reason, null, login));
    }

    @Transactional(readOnly = true)
    public Page<ActivityView> search(Long agentId, AgentAction action, Pageable pageable) {
        Page<AgentActivity> page = activityRepository.search(agentId, action, pageable);
        Set<Long> agentIds = page.getContent().stream().map(AgentActivity::getAgentId).collect(Collectors.toSet());
        Map<Long, User> agents = userRepository.findAllById(agentIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return page.map(a -> toView(a, agents.get(a.getAgentId())));
    }

    /** Date de la dernière occurrence d'une action, par agent (ex. dernière connexion). */
    @Transactional(readOnly = true)
    public Map<Long, Instant> lastDateByAgent(AgentAction action) {
        Map<Long, Instant> result = new HashMap<>();
        for (Object[] row : activityRepository.findLastDateByAgent(action)) {
            result.put((Long) row[0], (Instant) row[1]);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public long countSince(Long agentId, AgentAction action, Instant from) {
        return activityRepository.countByAgentIdAndActionAndCreatedDateGreaterThanEqual(agentId, action, from);
    }

    private static ActivityView toView(AgentActivity a, User agent) {
        return new ActivityView(
            a.getId(),
            a.getAgentId(),
            agent != null ? AgentAccountService.displayName(agent) : "Agent supprimé",
            agent != null ? agent.getEmail() : null,
            a.getAction(),
            a.getDescription(),
            a.getSalonId(),
            a.getActor(),
            a.getIpAddress(),
            a.getUserAgent(),
            a.getCreatedDate()
        );
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes ? attributes.getRequest() : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
