package com.sansfile.app.service.custom.deletion;

import com.sansfile.app.domain.CoiffeurProfile;
import com.sansfile.app.domain.Salon;
import com.sansfile.app.domain.User;
import com.sansfile.app.repository.AgentActivityRepository;
import com.sansfile.app.repository.AppNotificationRepository;
import com.sansfile.app.repository.AuthorityRepository;
import com.sansfile.app.repository.BoutiqueOrderRepository;
import com.sansfile.app.repository.CoiffeurProfileRepository;
import com.sansfile.app.repository.FavoriteSalonRepository;
import com.sansfile.app.repository.PushSubscriptionRepository;
import com.sansfile.app.repository.RelativeRepository;
import com.sansfile.app.repository.SalonActionRepository;
import com.sansfile.app.repository.SalonRepository;
import com.sansfile.app.repository.TicketRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.security.SecurityUtils;
import com.sansfile.app.service.custom.locality.LocalityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppressions demandées par l'administration, sans laisser de données orphelines (que la base refuserait).
 * <ul>
 *   <li>Compte : ses données personnelles partent avec lui (notifications, abonnements push, proches, favoris,
 *   profil coiffeur) ; ses tickets et commandes restent dans l'historique, sans lien vers le compte.</li>
 *   <li>Salon : seulement s'il n'a jamais eu de ticket (sinon on le désactive, pour garder l'historique) ; ses
 *   favoris et boutons partent avec lui, et son coiffeur redevient un simple client.</li>
 * </ul>
 */
@Service
@Transactional
public class DeletionService {

    private static final Logger LOG = LoggerFactory.getLogger(DeletionService.class);

    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final SalonRepository salonRepository;
    private final CoiffeurProfileRepository coiffeurProfileRepository;
    private final TicketRepository ticketRepository;
    private final BoutiqueOrderRepository boutiqueOrderRepository;
    private final AppNotificationRepository appNotificationRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final RelativeRepository relativeRepository;
    private final FavoriteSalonRepository favoriteSalonRepository;
    private final SalonActionRepository salonActionRepository;
    private final AgentActivityRepository agentActivityRepository;
    private final CacheManager cacheManager;

    public DeletionService(
        UserRepository userRepository,
        AuthorityRepository authorityRepository,
        SalonRepository salonRepository,
        CoiffeurProfileRepository coiffeurProfileRepository,
        TicketRepository ticketRepository,
        BoutiqueOrderRepository boutiqueOrderRepository,
        AppNotificationRepository appNotificationRepository,
        PushSubscriptionRepository pushSubscriptionRepository,
        RelativeRepository relativeRepository,
        FavoriteSalonRepository favoriteSalonRepository,
        SalonActionRepository salonActionRepository,
        AgentActivityRepository agentActivityRepository,
        CacheManager cacheManager
    ) {
        this.userRepository = userRepository;
        this.authorityRepository = authorityRepository;
        this.salonRepository = salonRepository;
        this.coiffeurProfileRepository = coiffeurProfileRepository;
        this.ticketRepository = ticketRepository;
        this.boutiqueOrderRepository = boutiqueOrderRepository;
        this.appNotificationRepository = appNotificationRepository;
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        this.relativeRepository = relativeRepository;
        this.favoriteSalonRepository = favoriteSalonRepository;
        this.salonActionRepository = salonActionRepository;
        this.agentActivityRepository = agentActivityRepository;
        this.cacheManager = cacheManager;
    }

    /** Supprime un compte et ses données personnelles ; tickets et commandes restent, sans lien vers lui. */
    public void deleteUser(String login) {
        User user = userRepository
            .findOneByLogin(login)
            .orElseThrow(() -> LocalityException.notFound("user-not-found", "Ce compte n'existe plus."));
        if (SecurityUtils.getCurrentUserLogin().filter(login::equals).isPresent()) {
            throw LocalityException.conflict("self-delete", "Vous ne pouvez pas supprimer votre propre compte.");
        }
        Long userId = user.getId();
        if (agentActivityRepository.countByAgentId(userId) > 0) {
            throw LocalityException.conflict(
                "agent-has-activity",
                "Ce compte est un agent de terrain qui a déjà exercé : désactivez-le depuis la page Agents pour garder son journal."
            );
        }

        coiffeurProfileRepository.deleteAll(coiffeurProfileRepository.findByUserId(userId));
        int tickets = ticketRepository.detachUser(userId);
        int orders = boutiqueOrderRepository.detachUser(userId);
        appNotificationRepository.deleteAllOfUser(userId);
        pushSubscriptionRepository.deleteAllOfUser(userId);
        relativeRepository.deleteAllOfUser(userId);
        favoriteSalonRepository.deleteAllOfUser(userId);
        userRepository.delete(user);
        userRepository.flush();
        evictUserCaches(user);
        LOG.info("Compte {} supprimé ({} ticket(s) et {} commande(s) gardés sans lien vers le compte)", userId, tickets, orders);
    }

    /**
     * Supprime un salon qui n'a jamais eu de ticket. Avec un historique, la suppression est refusée : le salon se
     * désactive (invisible des clients, plus aucun ticket) sans rien perdre.
     */
    public void deleteSalon(Long salonId) {
        Salon salon = salonRepository
            .findById(salonId)
            .orElseThrow(() -> LocalityException.notFound("salon-not-found", "Ce salon n'existe plus."));
        long tickets = ticketRepository.countBySalonId(salonId);
        if (tickets > 0) {
            throw LocalityException.conflict(
                "salon-has-tickets",
                "« " +
                    salon.getName() +
                    " » a déjà " +
                    tickets +
                    " ticket(s) : le supprimer effacerait son historique. Désactivez-le plutôt (il disparaît de l'application des clients)."
            );
        }

        for (CoiffeurProfile profile : coiffeurProfileRepository.findBySalonId(salonId)) {
            User coiffeur = profile.getUser();
            coiffeurProfileRepository.delete(profile);
            if (coiffeur != null) {
                becomeClient(coiffeur);
            }
        }
        favoriteSalonRepository.deleteAllOfSalon(salonId);
        salonActionRepository.deleteAllOfSalon(salonId);
        salonRepository.delete(salon);
        salonRepository.flush();
        LOG.info("Salon « {} » supprimé", salon.getName());
    }

    /** Le coiffeur d'un salon supprimé garde son compte, comme simple client. */
    private void becomeClient(User user) {
        boolean wasCoiffeur = user.getAuthorities().removeIf(a -> AuthoritiesConstants.COIFFEUR.equals(a.getName()));
        if (!wasCoiffeur) {
            return;
        }
        authorityRepository.findById(AuthoritiesConstants.CLIENT).ifPresent(user.getAuthorities()::add);
        authorityRepository.findById(AuthoritiesConstants.USER).ifPresent(user.getAuthorities()::add);
        userRepository.save(user);
        evictUserCaches(user);
    }

    private void evictUserCaches(User user) {
        Cache byLogin = cacheManager.getCache(UserRepository.USERS_BY_LOGIN_CACHE);
        if (byLogin != null && user.getLogin() != null) {
            byLogin.evictIfPresent(user.getLogin());
        }
        Cache byEmail = cacheManager.getCache(UserRepository.USERS_BY_EMAIL_CACHE);
        if (byEmail != null && user.getEmail() != null) {
            byEmail.evictIfPresent(user.getEmail());
        }
    }
}
