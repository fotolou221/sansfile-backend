package com.sansfile.app.web.rest.custom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sansfile.app.IntegrationTest;
import com.sansfile.app.domain.AgentActivity;
import com.sansfile.app.domain.Authority;
import com.sansfile.app.domain.BoutiqueOrder;
import com.sansfile.app.domain.CoiffeurProfile;
import com.sansfile.app.domain.FavoriteSalon;
import com.sansfile.app.domain.PushSubscription;
import com.sansfile.app.domain.Relative;
import com.sansfile.app.domain.Salon;
import com.sansfile.app.domain.Ticket;
import com.sansfile.app.domain.User;
import com.sansfile.app.domain.enumeration.AgentAction;
import com.sansfile.app.domain.enumeration.NotificationType;
import com.sansfile.app.domain.enumeration.OrderStatus;
import com.sansfile.app.domain.enumeration.OrderType;
import com.sansfile.app.domain.enumeration.RecipientRole;
import com.sansfile.app.domain.enumeration.RelativeRelation;
import com.sansfile.app.domain.enumeration.SalonStatus;
import com.sansfile.app.repository.AgentActivityRepository;
import com.sansfile.app.repository.AppNotificationRepository;
import com.sansfile.app.repository.AuthorityRepository;
import com.sansfile.app.repository.BoutiqueOrderRepository;
import com.sansfile.app.repository.CoiffeurProfileRepository;
import com.sansfile.app.repository.FavoriteSalonRepository;
import com.sansfile.app.repository.PushSubscriptionRepository;
import com.sansfile.app.repository.RelativeRepository;
import com.sansfile.app.repository.SalonRepository;
import com.sansfile.app.repository.TicketRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.service.custom.notification.NotificationDeliveryService;
import com.sansfile.app.service.custom.queue.QueueEngineService;
import com.sansfile.app.service.custom.queue.QueueEngineService.BeneficiaryItem;
import com.sansfile.app.service.dto.TicketDTO;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppressions depuis l'administration : plus de refus silencieux de la base (données rattachées), et un
 * historique (tickets, commandes) qui ne disparaît jamais.
 */
@AutoConfigureMockMvc
@IntegrationTest
@Transactional
class DeletionIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthorityRepository authorityRepository;

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private CoiffeurProfileRepository coiffeurProfileRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private BoutiqueOrderRepository boutiqueOrderRepository;

    @Autowired
    private AppNotificationRepository appNotificationRepository;

    @Autowired
    private PushSubscriptionRepository pushSubscriptionRepository;

    @Autowired
    private RelativeRepository relativeRepository;

    @Autowired
    private FavoriteSalonRepository favoriteSalonRepository;

    @Autowired
    private AgentActivityRepository agentActivityRepository;

    @Autowired
    private QueueEngineService queueEngineService;

    @Autowired
    private NotificationDeliveryService notificationDeliveryService;

    @Test
    void deletedClientLosesHisPersonalDataButTicketsAndOrdersStay() throws Exception {
        Salon salon = salonRepository.saveAndFlush(newSalon());
        User client = createUser(AuthoritiesConstants.CLIENT);
        Long clientId = client.getId();

        TicketDTO ticket = queueEngineService
            .bookTickets(salon.getId(), client, List.of(new BeneficiaryItem("Moi", "SELF", null, null)))
            .get(0);
        BoutiqueOrder order = boutiqueOrderRepository.saveAndFlush(newOrder(client));
        notificationDeliveryService.notifyInApp(client, RecipientRole.CLIENT, NotificationType.SYSTEM, "Bienvenue", "Bonjour", null);
        pushSubscriptionRepository.saveAndFlush(newPushSubscription(client));
        relativeRepository.saveAndFlush(newRelative(client));
        favoriteSalonRepository.saveAndFlush(newFavorite(client, salon));

        mockMvc.perform(delete("/api/admin/users/" + client.getLogin()).with(admin())).andExpect(status().isNoContent());
        em.flush();
        em.clear();

        assertThat(userRepository.findById(clientId)).isEmpty();
        assertThat(pushSubscriptionRepository.findByUserId(clientId)).isEmpty();
        assertThat(relativeRepository.countByUserId(clientId)).isZero();
        assertThat(appNotificationRepository.findAll()).noneMatch(n -> n.getUser() != null && clientId.equals(n.getUser().getId()));
        // Historique gardé, sans lien vers le compte
        Ticket kept = ticketRepository.findById(ticket.getId()).orElseThrow();
        assertThat(kept.getUser()).isNull();
        assertThat(boutiqueOrderRepository.findById(order.getId()).orElseThrow().getUser()).isNull();
    }

    @Test
    void deletedCoiffeurLeavesHisSalon() throws Exception {
        Salon salon = salonRepository.saveAndFlush(newSalon());
        User coiffeur = createUser(AuthoritiesConstants.COIFFEUR);
        coiffeurProfileRepository.saveAndFlush(newProfile(coiffeur, salon));

        mockMvc.perform(delete("/api/admin/users/" + coiffeur.getLogin()).with(admin())).andExpect(status().isNoContent());

        assertThat(userRepository.findById(coiffeur.getId())).isEmpty();
        assertThat(coiffeurProfileRepository.findBySalonId(salon.getId())).isEmpty();
        assertThat(salonRepository.findById(salon.getId())).isPresent();
    }

    @Test
    void salonWithoutHistoryIsDeletedAndItsCoiffeurBecomesAClient() throws Exception {
        Salon salon = salonRepository.saveAndFlush(newSalon());
        User coiffeur = createUser(AuthoritiesConstants.COIFFEUR);
        coiffeurProfileRepository.saveAndFlush(newProfile(coiffeur, salon));
        User fan = createUser(AuthoritiesConstants.CLIENT);
        favoriteSalonRepository.saveAndFlush(newFavorite(fan, salon));

        mockMvc.perform(delete("/api/salons/" + salon.getId()).with(admin())).andExpect(status().isNoContent());
        em.flush();
        em.clear();

        assertThat(salonRepository.findById(salon.getId())).isEmpty();
        assertThat(coiffeurProfileRepository.findBySalonId(salon.getId())).isEmpty();
        User formerCoiffeur = userRepository.findOneWithAuthoritiesByLogin(coiffeur.getLogin()).orElseThrow();
        assertThat(formerCoiffeur.getAuthorities())
            .extracting(Authority::getName)
            .contains(AuthoritiesConstants.CLIENT)
            .doesNotContain(AuthoritiesConstants.COIFFEUR);
    }

    @Test
    void salonWithTicketsIsNotDeleted() throws Exception {
        Salon salon = salonRepository.saveAndFlush(newSalon());
        queueEngineService.bookTickets(salon.getId(), (User) null, List.of(new BeneficiaryItem("Awa", "CUSTOM", null, null)));

        mockMvc
            .perform(delete("/api/salons/" + salon.getId()).with(admin()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("salon-has-tickets"));
        assertThat(salonRepository.findById(salon.getId())).isPresent();
    }

    @Test
    void agentWithActivityAndOwnAccountAreNotDeleted() throws Exception {
        User agent = createUser(AuthoritiesConstants.AGENT);
        AgentActivity activity = new AgentActivity();
        activity.setAgentId(agent.getId());
        activity.setAction(AgentAction.ACCOUNT_CREATED);
        activity.setDescription("Compte créé");
        activity.setActor("admin");
        activity.setCreatedDate(Instant.now());
        agentActivityRepository.saveAndFlush(activity);

        mockMvc
            .perform(delete("/api/admin/users/" + agent.getLogin()).with(admin()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("agent-has-activity"));

        User admin = createUser(AuthoritiesConstants.ADMIN);
        mockMvc
            .perform(
                delete("/api/admin/users/" + admin.getLogin()).with(
                    user(admin.getLogin()).authorities(new SimpleGrantedAuthority(AuthoritiesConstants.ADMIN))
                )
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("self-delete"));
    }

    // ── Outils ──────────────────────────────────────────────────

    private User createUser(String authority) {
        String phone = "+22177" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        User user = new User();
        user.setLogin(phone);
        user.setPhone(phone);
        user.setPassword("$2a$10$" + "x".repeat(53));
        user.setFirstName("Test");
        user.setLastName("Suppression");
        user.setEmail(phone.substring(1) + "@test.sansfile.sn");
        user.setActivated(true);
        user.setLangKey("fr");
        Set<Authority> authorities = new HashSet<>();
        authorityRepository.findById(authority).ifPresent(authorities::add);
        authorityRepository.findById(AuthoritiesConstants.USER).ifPresent(authorities::add);
        user.setAuthorities(authorities);
        return userRepository.saveAndFlush(user);
    }

    private static Salon newSalon() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Salon salon = new Salon();
        salon.setName("Salon " + suffix);
        salon.setSlug("salon-" + suffix);
        salon.setLocation("Dakar");
        salon.setDistrict("Médina");
        salon.setStatus(SalonStatus.OPEN);
        salon.setActive(true);
        salon.setPeopleWaiting(0);
        salon.setEstimatedWaitMinutes(0);
        salon.setCreatedDate(Instant.now());
        salon.setLastModifiedDate(Instant.now());
        return salon;
    }

    private static CoiffeurProfile newProfile(User coiffeur, Salon salon) {
        CoiffeurProfile profile = new CoiffeurProfile();
        profile.setName("Barbier Test");
        profile.setPhone(coiffeur.getLogin());
        profile.setActive(true);
        profile.setSalon(salon);
        profile.setUser(coiffeur);
        profile.setCreatedDate(Instant.now());
        return profile;
    }

    private static BoutiqueOrder newOrder(User client) {
        BoutiqueOrder order = new BoutiqueOrder();
        order.setOrderNumber("CMD-DEL-" + UUID.randomUUID().toString().substring(0, 6));
        order.setSubtotal(5_000L);
        order.setDeliveryFee(1_000L);
        order.setTotalPrice(6_000L);
        order.setStatus(OrderStatus.LIVRE);
        order.setOrderType(OrderType.WHATSAPP);
        order.setCreatedDate(Instant.now());
        order.setUser(client);
        return order;
    }

    private static PushSubscription newPushSubscription(User user) {
        PushSubscription subscription = new PushSubscription();
        subscription.setEndpoint("https://push.example/" + UUID.randomUUID());
        subscription.setP256dh("cle");
        subscription.setAuth("secret");
        subscription.setCreatedDate(Instant.now());
        subscription.setLastSeenDate(Instant.now());
        subscription.setUser(user);
        return subscription;
    }

    private static Relative newRelative(User user) {
        Relative relative = new Relative();
        relative.setName("Fatou");
        relative.setRelation(RelativeRelation.MERE);
        relative.setCreatedDate(Instant.now());
        relative.setUser(user);
        return relative;
    }

    private static FavoriteSalon newFavorite(User user, Salon salon) {
        FavoriteSalon favorite = new FavoriteSalon();
        favorite.setUser(user);
        favorite.setSalon(salon);
        favorite.setCreatedDate(Instant.now());
        return favorite;
    }

    private static RequestPostProcessor admin() {
        return user("admin-deletion-it").authorities(new SimpleGrantedAuthority(AuthoritiesConstants.ADMIN));
    }
}
