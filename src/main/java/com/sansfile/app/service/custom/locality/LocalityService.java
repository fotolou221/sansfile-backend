package com.sansfile.app.service.custom.locality;

import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.domain.Locality;
import com.sansfile.app.domain.Partner;
import com.sansfile.app.repository.AgentLocalityRepository;
import com.sansfile.app.repository.BoutiqueOrderRepository;
import com.sansfile.app.repository.LocalityRepository;
import com.sansfile.app.repository.PartnerRepository;
import com.sansfile.app.repository.SalonRepository;
import com.sansfile.app.service.custom.realtime.RealtimeEventService;
import com.sansfile.app.service.dto.SalonDTO;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Localités : zones créées par l'administration pour organiser SansFile en interne (salons, agents de terrain,
 * partenaires de la boutique et leurs frais de livraison, commandes). Les clients n'en choisissent pas : salons
 * et boutique suivent la position de leur téléphone. Une localité utilisée n'est jamais supprimée : on la désactive.
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
    private final AgentLocalityRepository agentLocalityRepository;
    private final BoutiqueOrderRepository boutiqueOrderRepository;
    private final ApplicationProperties applicationProperties;
    private final RealtimeEventService realtimeEventService;

    public LocalityService(
        LocalityRepository localityRepository,
        PartnerRepository partnerRepository,
        SalonRepository salonRepository,
        AgentLocalityRepository agentLocalityRepository,
        BoutiqueOrderRepository boutiqueOrderRepository,
        ApplicationProperties applicationProperties,
        RealtimeEventService realtimeEventService
    ) {
        this.localityRepository = localityRepository;
        this.partnerRepository = partnerRepository;
        this.salonRepository = salonRepository;
        this.agentLocalityRepository = agentLocalityRepository;
        this.boutiqueOrderRepository = boutiqueOrderRepository;
        this.applicationProperties = applicationProperties;
        this.realtimeEventService = realtimeEventService;
    }

    public record AdminLocality(
        Long id,
        String name,
        boolean active,
        long deliveryFee,
        Long partnerId,
        String partnerName,
        boolean partnerActive,
        long salonsCount,
        long agentsCount,
        long ordersCount,
        Instant createdDate
    ) {}

    public record LocalityForm(String name, Boolean active, Long deliveryFee) {}

    // ── Console d'administration ────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminLocality> adminList() {
        Map<Long, Partner> partners = partnersByLocality();
        Map<Long, Long> salons = counts(salonRepository.countSalonsByLocality());
        Map<Long, Long> agents = counts(agentLocalityRepository.countAgentsByLocality());
        Map<Long, Long> orders = counts(boutiqueOrderRepository.countOrdersByLocality());
        return localityRepository
            .findAllByOrderByNameAsc()
            .stream()
            .map(l -> toAdmin(l, partners.get(l.getId()), salons, agents, orders))
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

    /** Une localité désactivée ferme sa boutique et n'accepte plus de nouveaux salons ; les existants restent rattachés. */
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
        if (usage.partnerId() != null || usage.salonsCount() > 0 || usage.agentsCount() > 0 || usage.ordersCount() > 0) {
            throw LocalityException.conflict(
                "locality-in-use",
                "La localité « " +
                    locality.getName() +
                    " » est déjà utilisée (salons, agents, partenaire ou commandes). Désactivez-la plutôt."
            );
        }
        localityRepository.delete(locality);
        broadcast(id);
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

    public static String cleanName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    // ── Interne ─────────────────────────────────────────────────

    private AdminLocality adminView(Locality locality) {
        Long id = locality.getId();
        Partner partner = partnerRepository.findOneByLocalityId(id).orElse(null);
        return toAdmin(
            locality,
            partner,
            counts(salonRepository.countSalonsByLocality()),
            counts(agentLocalityRepository.countAgentsByLocality()),
            counts(boutiqueOrderRepository.countOrdersByLocality())
        );
    }

    private AdminLocality toAdmin(Locality l, Partner partner, Map<Long, Long> salons, Map<Long, Long> agents, Map<Long, Long> orders) {
        return new AdminLocality(
            l.getId(),
            l.getName(),
            l.isActive(),
            fee(l),
            partner != null ? partner.getId() : null,
            partner != null ? partner.getName() : null,
            partner != null && partner.isActive(),
            salons.getOrDefault(l.getId(), 0L),
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
