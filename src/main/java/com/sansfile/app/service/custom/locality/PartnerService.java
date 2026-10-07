package com.sansfile.app.service.custom.locality;

import com.sansfile.app.domain.Locality;
import com.sansfile.app.domain.Partner;
import com.sansfile.app.domain.PartnerProduct;
import com.sansfile.app.domain.Product;
import com.sansfile.app.domain.ProductImage;
import com.sansfile.app.repository.BoutiqueOrderRepository;
import com.sansfile.app.repository.LocalityRepository;
import com.sansfile.app.repository.PartnerProductRepository;
import com.sansfile.app.repository.PartnerRepository;
import com.sansfile.app.repository.ProductImageRepository;
import com.sansfile.app.repository.ProductRepository;
import com.sansfile.app.service.custom.realtime.RealtimeEventService;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partenaires boutique (un par localité) et leurs produits : prix de gros, disponibilité et quantité en stock,
 * gérés par l'administration. Un produit est commandable dans une localité si son partenaire est actif, l'a
 * marqué disponible et en a encore en stock. Prix de gros et marges ne sortent jamais de la console
 * d'administration (ni des factures envoyées au partenaire, qui ne montrent que ses propres prix).
 */
@Service
@Transactional
public class PartnerService {

    private static final Logger LOG = LoggerFactory.getLogger(PartnerService.class);
    public static final String SHOP_UPDATED = "SHOP_UPDATED";

    private final PartnerRepository partnerRepository;
    private final PartnerProductRepository partnerProductRepository;
    private final LocalityRepository localityRepository;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final BoutiqueOrderRepository boutiqueOrderRepository;
    private final RealtimeEventService realtimeEventService;

    public PartnerService(
        PartnerRepository partnerRepository,
        PartnerProductRepository partnerProductRepository,
        LocalityRepository localityRepository,
        ProductRepository productRepository,
        ProductImageRepository productImageRepository,
        BoutiqueOrderRepository boutiqueOrderRepository,
        RealtimeEventService realtimeEventService
    ) {
        this.partnerRepository = partnerRepository;
        this.partnerProductRepository = partnerProductRepository;
        this.localityRepository = localityRepository;
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.boutiqueOrderRepository = boutiqueOrderRepository;
        this.realtimeEventService = realtimeEventService;
    }

    public record AdminPartner(
        Long id,
        String name,
        String managerName,
        String phone,
        String address,
        Long localityId,
        String localityName,
        boolean active,
        String courierName,
        String courierPhone,
        String notes,
        long availableProducts,
        Instant createdDate
    ) {}

    public record PartnerForm(
        String name,
        String managerName,
        String phone,
        String address,
        Long localityId,
        Boolean active,
        String courierName,
        String courierPhone,
        String notes
    ) {}

    /**
     * Ligne du catalogue vue depuis un partenaire : son offre (prix de gros, disponibilité, quantité en stock)
     * et la marge SansFile.
     */
    public record PartnerProductRow(
        Long productId,
        String title,
        String brand,
        String image,
        long salePrice,
        boolean productInStock,
        boolean offered,
        Long wholesalePrice,
        boolean available,
        Long margin,
        int stockQuantity
    ) {}

    /** {@code stockQuantity} : quantité en stock chez le partenaire (inchangée si absente). */
    public record OfferForm(Long wholesalePrice, Boolean available, Integer stockQuantity) {}

    public static final int MAX_STOCK = 100_000;

    // ── Partenaires ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminPartner> list() {
        Map<Long, Long> available = availableCounts();
        return partnerRepository
            .findAllByOrderByNameAsc()
            .stream()
            .map(p -> toAdmin(p, available.getOrDefault(p.getId(), 0L)))
            .toList();
    }

    @Transactional(readOnly = true)
    public AdminPartner get(Long id) {
        Partner partner = find(id);
        return toAdmin(partner, availableCounts().getOrDefault(id, 0L));
    }

    public AdminPartner create(PartnerForm form) {
        Partner partner = new Partner();
        partner.setCreatedDate(Instant.now());
        apply(partner, form);
        partner.setActive(form.active() == null || form.active());
        partner = partnerRepository.saveAndFlush(partner);
        broadcastShop(partner.getLocality().getId());
        return get(partner.getId());
    }

    public AdminPartner update(Long id, PartnerForm form) {
        Partner partner = find(id);
        Long previousLocality = partner.getLocality().getId();
        apply(partner, form);
        if (form.active() != null) {
            partner.setActive(form.active());
        }
        partner = partnerRepository.saveAndFlush(partner);
        broadcastShop(previousLocality);
        if (!previousLocality.equals(partner.getLocality().getId())) {
            broadcastShop(partner.getLocality().getId());
        }
        return get(id);
    }

    /** Partenaire désactivé : la boutique de sa localité est fermée jusqu'à sa réactivation. */
    public AdminPartner setActive(Long id, boolean active) {
        Partner partner = find(id);
        partner.setActive(active);
        partner.setLastModifiedDate(Instant.now());
        partnerRepository.saveAndFlush(partner);
        broadcastShop(partner.getLocality().getId());
        return get(id);
    }

    /** Suppression réservée aux partenaires sans commande ; sinon, le désactiver (l'historique reste juste). */
    public void delete(Long id) {
        Partner partner = find(id);
        if (boutiqueOrderRepository.existsByPartnerId(id)) {
            throw LocalityException.conflict(
                "partner-has-orders",
                "Ce partenaire a déjà des commandes : désactivez-le plutôt que de le supprimer."
            );
        }
        Long localityId = partner.getLocality().getId();
        partnerRepository.delete(partner);
        broadcastShop(localityId);
    }

    // ── Produits du partenaire ──────────────────────────────────

    /** Tout le catalogue, avec l'offre du partenaire pour chaque produit (ou rien s'il ne le propose pas). */
    @Transactional(readOnly = true)
    public List<PartnerProductRow> products(Long partnerId) {
        find(partnerId);
        Map<Long, PartnerProduct> offers = partnerProductRepository
            .findAllByPartnerIdWithProduct(partnerId)
            .stream()
            .collect(Collectors.toMap(pp -> pp.getProduct().getId(), Function.identity()));
        return productRepository
            .findAll(Sort.by("title"))
            .stream()
            .map(product -> toRow(product, offers.get(product.getId())))
            .sorted(Comparator.comparing(PartnerProductRow::offered).reversed())
            .toList();
    }

    public PartnerProductRow saveOffer(Long partnerId, Long productId, OfferForm form) {
        Partner partner = find(partnerId);
        Product product = productRepository
            .findById(productId)
            .orElseThrow(() -> LocalityException.notFound("product-not-found", "Produit introuvable."));
        Long wholesale = form.wholesalePrice();
        if (wholesale == null || wholesale < 0) {
            throw LocalityException.invalid("invalid-wholesale-price", "Indiquez le prix de gros du partenaire (en FCFA).");
        }
        if (wholesale > product.getPrice()) {
            throw LocalityException.invalid(
                "wholesale-above-price",
                String.format(
                    "Le prix de gros (%,d FCFA) dépasse le prix de vente SansFile de « %s » (%,d FCFA) : la part SansFile serait négative.",
                    wholesale,
                    product.getTitle(),
                    product.getPrice()
                )
            );
        }
        Integer stock = form.stockQuantity();
        if (stock != null && (stock < 0 || stock > MAX_STOCK)) {
            throw LocalityException.invalid(
                "invalid-stock",
                String.format("Indiquez la quantité en stock chez le partenaire (0 à %,d).", MAX_STOCK)
            );
        }
        PartnerProduct offer = partnerProductRepository.findOneByPartnerIdAndProductId(partnerId, productId).orElseGet(() -> {
            PartnerProduct created = new PartnerProduct();
            created.setPartner(partner);
            created.setProduct(product);
            return created;
        });
        offer.setWholesalePrice(wholesale);
        offer.setAvailable(form.available() == null || form.available());
        if (stock != null) {
            offer.setStockQuantity(stock);
        }
        offer.setLastModifiedDate(Instant.now());
        partnerProductRepository.saveAndFlush(offer);
        broadcastShop(partner.getLocality().getId());
        return toRow(product, offer);
    }

    public void removeOffer(Long partnerId, Long productId) {
        Partner partner = find(partnerId);
        partnerProductRepository.findOneByPartnerIdAndProductId(partnerId, productId).ifPresent(offer -> {
            partnerProductRepository.delete(offer);
            broadcastShop(partner.getLocality().getId());
        });
    }

    // ── Boutique par localité ───────────────────────────────────

    /** Partenaire actif d'une localité active : sans lui, la boutique n'y est pas ouverte. */
    @Transactional(readOnly = true)
    public Optional<Partner> activePartnerOf(Long localityId) {
        return partnerRepository
            .findOneByLocalityId(localityId)
            .filter(Partner::isActive)
            .filter(p -> p.getLocality().isActive());
    }

    /** Produits commandables dans la localité (vide si la boutique n'y est pas ouverte). */
    @Transactional(readOnly = true)
    public List<Long> availableProductIds(Long localityId) {
        if (activePartnerOf(localityId).isEmpty()) {
            return List.of();
        }
        return partnerProductRepository.findAvailableProductIdsByLocalityId(localityId);
    }

    /** Offre du partenaire pour ce produit (prix de gros, stock), s'il le propose et en a encore. */
    @Transactional(readOnly = true)
    public Optional<PartnerProduct> availableOffer(Partner partner, Long productId) {
        return partnerProductRepository.findOneByPartnerIdAndProductId(partner.getId(), productId).filter(PartnerProduct::isOrderable);
    }

    // ── Stock du partenaire ─────────────────────────────────────

    /**
     * Retire les quantités d'une commande du stock du partenaire (acompte reçu). Tout ou rien : si un article
     * manque, rien n'est retiré et la confirmation est refusée avec le stock restant.
     */
    public void deductStock(Long partnerId, Map<Long, Integer> quantities) {
        Partner partner = find(partnerId);
        Map<Long, PartnerProduct> offers = new HashMap<>();
        for (Map.Entry<Long, Integer> line : quantities.entrySet()) {
            PartnerProduct offer = partnerProductRepository.findForUpdate(partnerId, line.getKey()).orElse(null);
            int remaining = offer == null || !offer.isAvailable() ? 0 : offer.getStockQuantity();
            if (remaining < line.getValue()) {
                String title =
                    offer != null
                        ? offer.getProduct().getTitle()
                        : productRepository.findById(line.getKey()).map(Product::getTitle).orElse("Article");
                throw LocalityException.conflict(
                    "stock-insufficient",
                    String.format(
                        "Stock insuffisant chez %s pour « %s » : %d commandé(s), %d en stock. Mettez à jour son stock ou annulez la commande.",
                        partner.getName(),
                        title,
                        line.getValue(),
                        remaining
                    )
                );
            }
            offers.put(line.getKey(), offer);
        }
        // Tout est disponible (offres verrouillées) : on retire
        offers.forEach((productId, offer) -> {
            offer.setStockQuantity(offer.getStockQuantity() - quantities.get(productId));
            offer.setLastModifiedDate(Instant.now());
        });
        partnerProductRepository.flush();
        broadcastShop(partner.getLocality().getId());
    }

    /** Remet en stock les quantités d'une commande annulée (offres retirées entre-temps : ignorées). */
    public void restoreStock(Long partnerId, Map<Long, Integer> quantities) {
        Partner partner = find(partnerId);
        for (Map.Entry<Long, Integer> line : quantities.entrySet()) {
            partnerProductRepository.findForUpdate(partnerId, line.getKey()).ifPresent(offer -> {
                offer.setStockQuantity(Math.min(MAX_STOCK, offer.getStockQuantity() + line.getValue()));
                offer.setLastModifiedDate(Instant.now());
            });
        }
        partnerProductRepository.flush();
        broadcastShop(partner.getLocality().getId());
    }

    /** Refuse un prix de vente inférieur au prix de gros d'un partenaire (part SansFile négative). */
    @Transactional(readOnly = true)
    public void assertSalePriceCoversWholesale(Long productId, Long salePrice) {
        if (productId == null || salePrice == null) {
            return;
        }
        List<PartnerProduct> above = partnerProductRepository.findAllByProductIdAndWholesalePriceAbove(productId, salePrice);
        if (!above.isEmpty()) {
            String partners = above
                .stream()
                .map(pp -> pp.getPartner().getName() + " (" + String.format("%,d", pp.getWholesalePrice()) + " FCFA)")
                .collect(Collectors.joining(", "));
            throw new IllegalStateException(
                "Le prix de vente ne peut pas être inférieur au prix de gros des partenaires : " +
                    partners +
                    ". Modifiez d'abord leur prix de gros."
            );
        }
    }

    // ── Interne ─────────────────────────────────────────────────

    private void apply(Partner partner, PartnerForm form) {
        String name = LocalityService.cleanName(form.name());
        if (name.length() < 2 || name.length() > 150) {
            throw LocalityException.invalid("invalid-name", "Renseignez le nom du partenaire (2 à 150 caractères).");
        }
        String phone = normalizePhone(form.phone(), true, "Renseignez le numéro WhatsApp du partenaire (ex. 77 123 45 67).");
        if (form.localityId() == null) {
            throw LocalityException.invalid("invalid-locality", "Choisissez la localité du partenaire.");
        }
        Locality locality = localityRepository
            .findById(form.localityId())
            .orElseThrow(() -> LocalityException.invalid("invalid-locality", "Cette localité n'existe pas."));
        partnerRepository
            .findOneByLocalityId(locality.getId())
            .filter(other -> !other.getId().equals(partner.getId()))
            .ifPresent(other -> {
                throw LocalityException.conflict(
                    "locality-has-partner",
                    "La localité « " + locality.getName() + " » a déjà un partenaire (" + other.getName() + ")."
                );
            });
        partner.setName(name);
        partner.setManagerName(optional(form.managerName(), 100));
        partner.setPhone(phone);
        partner.setAddress(optional(form.address(), 255));
        partner.setLocality(locality);
        partner.setCourierName(optional(form.courierName(), 100));
        partner.setCourierPhone(normalizePhone(form.courierPhone(), false, "Numéro du livreur invalide."));
        partner.setNotes(optional(form.notes(), 1000));
        partner.setLastModifiedDate(Instant.now());
    }

    private PartnerProductRow toRow(Product product, PartnerProduct offer) {
        long salePrice = product.getPrice() == null ? 0L : product.getPrice();
        Long wholesale = offer != null ? offer.getWholesalePrice() : null;
        return new PartnerProductRow(
            product.getId(),
            product.getTitle(),
            product.getBrand(),
            productImageRepository
                .findByProductIdOrderBySortOrderAsc(product.getId())
                .stream()
                .map(ProductImage::getImageUrl)
                .findFirst()
                .orElse(null),
            salePrice,
            Boolean.TRUE.equals(product.getInStock()),
            offer != null,
            wholesale,
            offer != null && offer.isAvailable(),
            wholesale != null ? salePrice - wholesale : null,
            offer != null ? offer.getStockQuantity() : 0
        );
    }

    private AdminPartner toAdmin(Partner p, long availableProducts) {
        return new AdminPartner(
            p.getId(),
            p.getName(),
            p.getManagerName(),
            p.getPhone(),
            p.getAddress(),
            p.getLocality().getId(),
            p.getLocality().getName(),
            p.isActive(),
            p.getCourierName(),
            p.getCourierPhone(),
            p.getNotes(),
            availableProducts,
            p.getCreatedDate()
        );
    }

    private Map<Long, Long> availableCounts() {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : partnerProductRepository.countAvailableByPartner()) {
            map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return map;
    }

    private Partner find(Long id) {
        return partnerRepository.findById(id).orElseThrow(() -> LocalityException.notFound("partner-not-found", "Partenaire introuvable."));
    }

    private static String optional(String raw, int max) {
        String value = LocalityService.cleanName(raw);
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** Format des comptes SansFile (+221XXXXXXXXX). */
    static String normalizePhone(String raw, boolean required, String message) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw LocalityException.invalid("invalid-phone", message);
            }
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
            throw LocalityException.invalid("invalid-phone", message);
        }
        return "+" + digits;
    }

    private void broadcastShop(Long localityId) {
        try {
            realtimeEventService.broadcast(SHOP_UPDATED, Map.of("localityId", localityId));
        } catch (Exception e) {
            LOG.warn("Diffusion temps réel {} impossible : {}", SHOP_UPDATED, e.getMessage());
        }
    }
}
