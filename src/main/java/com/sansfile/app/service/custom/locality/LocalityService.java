package com.sansfile.app.service.custom.locality;

import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.domain.CoiffeurProfile;
import com.sansfile.app.domain.Locality;
import com.sansfile.app.domain.Partner;
import com.sansfile.app.domain.User;
import com.sansfile.app.repository.AgentLocalityRepository;
import com.sansfile.app.repository.BoutiqueOrderRepository;
import com.sansfile.app.repository.CoiffeurProfileRepository;
import com.sansfile.app.repository.LocalityRepository;
import com.sansfile.app.repository.PartnerRepository;
import com.sansfile.app.repository.SalonRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.service.custom.realtime.RealtimeEventService;
import com.sansfile.app.service.dto.SalonDTO;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Localités : créées par l'administration, choisies par les clients et les coiffeurs après leur
 * connexion, et utilisées pour répartir salons, agents de terrain et commandes de la boutique.
 * Une localité utilisée n'est jamais supprimée : on la désactive.
 */
@Service
@Transactional
public class LocalityService {

    private static final Logger LOG = LoggerFactory.getLogger(LocalityService.class);
    private static final int MIN_NAME = 2;
    private static final int MAX_NAME = 100;
    public static final String LOCALITIES_UPDATED = "LOCALITIES_UPDATED";

    private final LocalityRepository localityRepository;
    private final PartnerRepository partnerRepository;
    private final SalonRepository salonRepository;
    private final UserRepository userRepository;
    private final AgentLocalityRepository agentLocalityRepository;
    private final BoutiqueOrderRepository boutiqueOrderRepository;
    private final CoiffeurProfileRepository coiffeurProfileRepository;
    private final ApplicationProperties applicationProperties;
    private final RealtimeEventService realtimeEventService;
    private final CacheManager cacheManager;

    public LocalityService(
        LocalityRepository localityRepository,
        PartnerRepository partnerRepository,
        SalonRepository salonRepository,
        UserRepository userRepository,
        AgentLocalityRepository agentLocalityRepository,
        BoutiqueOrderRepository boutiqueOrderRepository,
        CoiffeurProfileRepository coiffeurProfileRepository,
        ApplicationProperties applicationProperties,
        RealtimeEventService realtimeEventService,
        CacheManager cacheManager
    ) {
        this.localityRepository = localityRepository;
        this.partnerRepository = partnerRepository;
        this.salonRepository = salonRepository;
        this.userRepository = userRepository;
        this.agentLocalityRepository = agentLocalityRepository;
        this.boutiqueOrderRepository = boutiqueOrderRepository;
        this.coiffeurProfileRepository = coiffeurProfileRepository;
        this.applicationProperties = applicationProperties;
        this.realtimeEventService = realtimeEventService;
        this.cacheManager = cacheManager;
    }

    /** Localité telle que la voient les clients : la boutique n'y est ouverte qu'avec un partenaire actif. */
    public record LocalityView(Long id, String name, long deliveryFee, boolean shopAvailable) {}

    public record AdminLocality(
        Long id,
        String name,
        boolean active,
        long deliveryFee,
        Long partnerId,
        String partnerName,
        boolean partnerActive,
        long salonsCount,
        long usersCount,
        long agentsCount,
        long ordersCount,
        Instant createdDate
    ) {}

    public record LocalityForm(String name, Boolean active, Long deliveryFee) {}

    /** Zone demandée par des utilisateurs (« ma localité n'est pas dans la liste »). */
    public record RequestedZone(String name, long count) {}

    /**
     * Localité du compte connecté. {@code source} : SALON (coiffeur : celle de son salon, non modifiable),
     * USER (choisie par l'utilisateur) ou NONE (à choisir ; {@code requestedLocality} si zone demandée).
     */
    public record AccountLocality(
        Long localityId,
        String localityName,
        Long deliveryFee,
        boolean shopAvailable,
        String requestedLocality,
        String source,
        boolean chosen
    ) {}

    public record AccountLocalityRequest(Long localityId, String requestedLocality) {}

    // ── Lecture publique ────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LocalityView> publicList() {
        Map<Long, Partner> partners = partnersByLocality();
        return localityRepository
            .findAllByActiveTrueOrderByNameAsc()
            .stream()
            .map(l -> new LocalityView(l.getId(), l.getName(), fee(l), shopAvailable(partners.get(l.getId()))))
            .toList();
    }

    // ── Console d'administration ────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminLocality> adminList() {
        Map<Long, Partner> partners = partnersByLocality();
        Map<Long, Long> salons = counts(salonRepository.countSalonsByLocality());
        Map<Long, Long> users = counts(userRepository.countUsersByLocality());
        Map<Long, Long> agents = counts(agentLocalityRepository.countAgentsByLocality());
        Map<Long, Long> orders = counts(boutiqueOrderRepository.countOrdersByLocality());
        return localityRepository
            .findAllByOrderByNameAsc()
            .stream()
            .map(l -> toAdmin(l, partners.get(l.getId()), salons, users, agents, orders))
            .toList();
    }

    public AdminLocality create(LocalityForm form) {
        String name = validName(form.name());
        assertNameFree(name, null);
        Locality locality = new Locality();
        locality.setName(name);
        locality.setActive(form.active() == null || form.active());
        locality.setDeliveryFee(validFee(form.deliveryFee(), applicationProperties.getBusiness().getDeliveryFee()));
        locality.setCreatedDate(Instant.now());
        locality.setLastModifiedDate(locality.getCreatedDate());
        locality = localityRepository.saveAndFlush(locality);

        int attached = attachWaitingUsers(locality);
        if (attached > 0) {
            LOG.info("Localité « {} » créée : {} utilisateur(s) qui l'avaient demandée y sont rattachés", name, attached);
        }
        broadcast(locality.getId());
        return adminView(locality);
    }

    public AdminLocality update(Long id, LocalityForm form) {
        Locality locality = find(id);
        String name = validName(form.name());
        assertNameFree(name, id);
        locality.setName(name);
        if (form.active() != null) {
            locality.setActive(form.active());
        }
        locality.setDeliveryFee(validFee(form.deliveryFee(), fee(locality)));
        locality.setLastModifiedDate(Instant.now());
        locality = localityRepository.saveAndFlush(locality);
        broadcast(id);
        return adminView(locality);
    }

    /** Une localité désactivée n'est plus proposée aux clients ; ses salons et comptes restent rattachés. */
    public AdminLocality setActive(Long id, boolean active) {
        Locality locality = find(id);
        locality.setActive(active);
        locality.setLastModifiedDate(Instant.now());
        locality = localityRepository.saveAndFlush(locality);
        broadcast(id);
        return adminView(locality);
    }

    /** Suppression réservée aux localités jamais utilisées (ex. faute de frappe) ; sinon, désactiver. */
    public void delete(Long id) {
        Locality locality = find(id);
        AdminLocality usage = adminView(locality);
        if (
            usage.partnerId() != null ||
            usage.salonsCount() > 0 ||
            usage.usersCount() > 0 ||
            usage.agentsCount() > 0 ||
            usage.ordersCount() > 0
        ) {
            throw LocalityException.conflict(
                "locality-in-use",
                "La localité « " +
                    locality.getName() +
                    " » est déjà utilisée (salons, comptes, agents, partenaire ou commandes). Désactivez-la plutôt."
            );
        }
        localityRepository.delete(locality);
        broadcast(id);
    }

    @Transactional(readOnly = true)
    public List<RequestedZone> requestedZones() {
        // Regroupe sans tenir compte des majuscules ni des espaces ; garde l'orthographe la plus fréquente
        Map<String, Map<String, Long>> byKey = new LinkedHashMap<>();
        for (String raw : userRepository.findRequestedLocalities()) {
            String clean = cleanName(raw);
            if (clean.isEmpty()) {
                continue;
            }
            byKey.computeIfAbsent(clean.toLowerCase(Locale.ROOT), k -> new HashMap<>()).merge(clean, 1L, Long::sum);
        }
        return byKey
            .values()
            .stream()
            .map(spellings ->
                new RequestedZone(
                    spellings.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(""),
                    spellings.values().stream().mapToLong(Long::longValue).sum()
                )
            )
            .sorted(Comparator.comparingLong(RequestedZone::count).reversed().thenComparing(RequestedZone::name))
            .toList();
    }

    // ── Localité du compte (client, coiffeur) ───────────────────

    @Transactional(readOnly = true)
    public AccountLocality accountLocality(String login) {
        return accountLocality(findUser(login));
    }

    @Transactional(readOnly = true)
    public AccountLocality accountLocality(User user) {
        Map<Long, Partner> partners = partnersByLocality();
        Optional<Locality> salonLocality = salonLocalityOf(user.getLogin());
        if (salonLocality.isPresent()) {
            Locality l = salonLocality.get();
            return new AccountLocality(l.getId(), l.getName(), fee(l), shopAvailable(l, partners), null, "SALON", true);
        }
        if (user.getLocalityId() != null) {
            Optional<Locality> chosen = localityRepository.findById(user.getLocalityId());
            if (chosen.isPresent()) {
                Locality l = chosen.get();
                return new AccountLocality(l.getId(), l.getName(), fee(l), shopAvailable(l, partners), null, "USER", true);
            }
        }
        String requested = user.getRequestedLocality();
        boolean chosen = requested != null && !requested.isBlank();
        return new AccountLocality(null, null, null, false, chosen ? requested : null, "NONE", chosen);
    }

    /**
     * Localité du compte, sans le détail (boutique) : celle du salon pour un coiffeur, sinon celle choisie.
     * Null sans connexion ou sans localité.
     */
    @Transactional(readOnly = true)
    public Long accountLocalityId(String login) {
        if (login == null) {
            return null;
        }
        Optional<Locality> salonLocality = salonLocalityOf(login);
        if (salonLocality.isPresent()) {
            return salonLocality.get().getId();
        }
        return userRepository.findOneByLogin(login).map(User::getLocalityId).filter(localityRepository::existsById).orElse(null);
    }

    /**
     * Choix de la localité par le client ou le coiffeur : une localité active, ou une zone pas encore
     * ouverte (texte libre). Le coiffeur dont le salon a une localité garde celle du salon.
     */
    public AccountLocality setAccountLocality(String login, AccountLocalityRequest request) {
        User user = findUser(login);
        if (salonLocalityOf(login).isPresent()) {
            throw LocalityException.invalid(
                "locality-from-salon",
                "Votre localité est celle de votre salon. Contactez l'administration pour la changer."
            );
        }
        if (request.localityId() != null) {
            Locality locality = requireActive(request.localityId());
            user.setLocalityId(locality.getId());
            user.setRequestedLocality(null);
        } else {
            String requested = cleanName(request.requestedLocality());
            if (requested.length() < MIN_NAME || requested.length() > MAX_NAME) {
                throw LocalityException.invalid("invalid-locality", "Choisissez votre localité dans la liste ou indiquez son nom.");
            }
            Optional<Locality> existing = localityRepository.findOneByNameIgnoreCase(requested).filter(Locality::isActive);
            user.setLocalityId(existing.map(Locality::getId).orElse(null));
            user.setRequestedLocality(existing.isPresent() ? null : requested);
        }
        userRepository.saveAndFlush(user);
        evictUserCaches(user);
        return accountLocality(user);
    }

    // ── Outils pour les autres services ─────────────────────────

    /** Localité existante et active, sinon refus (400). */
    @Transactional(readOnly = true)
    public Locality requireActive(Long localityId) {
        Locality locality = localityRepository
            .findById(localityId)
            .orElseThrow(() -> LocalityException.invalid("invalid-locality", "Cette localité n'existe pas."));
        if (!locality.isActive()) {
            throw LocalityException.invalid("inactive-locality", "La localité « " + locality.getName() + " » n'est plus proposée.");
        }
        return locality;
    }

    @Transactional(readOnly = true)
    public Map<Long, String> namesById() {
        return localityRepository.findAll().stream().collect(Collectors.toMap(Locality::getId, Locality::getName));
    }

    /** Ajoute le nom de la localité aux salons (affichage des cartes, filtres). */
    @Transactional(readOnly = true)
    public <T extends Collection<SalonDTO>> T fillSalonLocalities(T salons) {
        if (salons.stream().anyMatch(s -> s.getLocalityId() != null)) {
            Map<Long, String> names = namesById();
            salons.forEach(s -> s.setLocalityName(s.getLocalityId() == null ? null : names.get(s.getLocalityId())));
        }
        return salons;
    }

    @Transactional(readOnly = true)
    public SalonDTO fillSalonLocality(SalonDTO salon) {
        if (salon != null) {
            salon.setLocalityName(
                salon.getLocalityId() == null
                    ? null
                    : localityRepository.findById(salon.getLocalityId()).map(Locality::getName).orElse(null)
            );
        }
        return salon;
    }

    /** Les comptes sont mis en cache à la connexion : sans cela, l'ancienne localité serait relue. */
    public void evictUserCaches(User user) {
        Cache byLogin = cacheManager.getCache(UserRepository.USERS_BY_LOGIN_CACHE);
        if (byLogin != null && user.getLogin() != null) {
            byLogin.evictIfPresent(user.getLogin());
        }
        Cache byEmail = cacheManager.getCache(UserRepository.USERS_BY_EMAIL_CACHE);
        if (byEmail != null && user.getEmail() != null) {
            byEmail.evictIfPresent(user.getEmail());
        }
    }

    public static String cleanName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    // ── Interne ─────────────────────────────────────────────────

    private Optional<Locality> salonLocalityOf(String login) {
        return coiffeurProfileRepository
            .findOneWithSalonByUserLogin(login)
            .map(CoiffeurProfile::getSalon)
            .filter(salon -> salon.getLocalityId() != null)
            .flatMap(salon -> localityRepository.findById(salon.getLocalityId()));
    }

    /** Utilisateurs qui avaient demandé cette zone avant sa création : rattachés automatiquement. */
    private int attachWaitingUsers(Locality locality) {
        List<User> waiting = userRepository.findAllWaitingForLocality(locality.getName());
        for (User user : waiting) {
            user.setLocalityId(locality.getId());
            user.setRequestedLocality(null);
            userRepository.save(user);
            evictUserCaches(user);
        }
        return waiting.size();
    }

    private AdminLocality adminView(Locality locality) {
        Long id = locality.getId();
        Partner partner = partnerRepository.findOneByLocalityId(id).orElse(null);
        return toAdmin(
            locality,
            partner,
            counts(salonRepository.countSalonsByLocality()),
            counts(userRepository.countUsersByLocality()),
            counts(agentLocalityRepository.countAgentsByLocality()),
            counts(boutiqueOrderRepository.countOrdersByLocality())
        );
    }

    private AdminLocality toAdmin(
        Locality l,
        Partner partner,
        Map<Long, Long> salons,
        Map<Long, Long> users,
        Map<Long, Long> agents,
        Map<Long, Long> orders
    ) {
        return new AdminLocality(
            l.getId(),
            l.getName(),
            l.isActive(),
            fee(l),
            partner != null ? partner.getId() : null,
            partner != null ? partner.getName() : null,
            partner != null && partner.isActive(),
            salons.getOrDefault(l.getId(), 0L),
            users.getOrDefault(l.getId(), 0L),
            agents.getOrDefault(l.getId(), 0L),
            orders.getOrDefault(l.getId(), 0L),
            l.getCreatedDate()
        );
    }

    private Map<Long, Partner> partnersByLocality() {
        return partnerRepository
            .findAll()
            .stream()
            .collect(Collectors.toMap(p -> p.getLocality().getId(), Function.identity(), (a, b) -> a.isActive() ? a : b));
    }

    private boolean shopAvailable(Locality locality, Map<Long, Partner> partners) {
        return locality.isActive() && shopAvailable(partners.get(locality.getId()));
    }

    private static boolean shopAvailable(Partner partner) {
        return partner != null && partner.isActive();
    }

    private static Map<Long, Long> counts(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            }
        }
        return map;
    }

    private static long fee(Locality locality) {
        return locality.getDeliveryFee() == null ? 0L : locality.getDeliveryFee();
    }

    private Locality find(Long id) {
        return localityRepository.findById(id).orElseThrow(() -> LocalityException.notFound("locality-not-found", "Localité introuvable."));
    }

    private User findUser(String login) {
        return userRepository
            .findOneByLogin(login)
            .orElseThrow(() -> LocalityException.notFound("user-not-found", "Compte introuvable. Reconnectez-vous."));
    }

    private static String validName(String raw) {
        String name = cleanName(raw);
        if (name.length() < MIN_NAME || name.length() > MAX_NAME) {
            throw LocalityException.invalid(
                "invalid-name",
                "Le nom de la localité doit contenir entre " + MIN_NAME + " et " + MAX_NAME + " caractères."
            );
        }
        return name;
    }

    private static long validFee(Long fee, long fallback) {
        if (fee == null) {
            return fallback;
        }
        if (fee < 0 || fee > 1_000_000) {
            throw LocalityException.invalid("invalid-fee", "Frais de livraison invalides.");
        }
        return fee;
    }

    private void assertNameFree(String name, Long id) {
        localityRepository
            .findOneByNameIgnoreCase(name)
            .filter(existing -> !existing.getId().equals(id))
            .ifPresent(existing -> {
                throw LocalityException.conflict("locality-exists", "La localité « " + existing.getName() + " » existe déjà.");
            });
    }

    private void broadcast(Long id) {
        try {
            realtimeEventService.broadcast(LOCALITIES_UPDATED, Map.of("id", id));
        } catch (Exception e) {
            LOG.warn("Diffusion temps réel {} impossible : {}", LOCALITIES_UPDATED, e.getMessage());
        }
    }
}
