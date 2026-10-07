package com.sansfile.app.service.custom.order.impl;

import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.domain.AppNotification;
import com.sansfile.app.domain.BoutiqueOrder;
import com.sansfile.app.domain.Locality;
import com.sansfile.app.domain.OrderItem;
import com.sansfile.app.domain.Partner;
import com.sansfile.app.domain.PartnerProduct;
import com.sansfile.app.domain.Product;
import com.sansfile.app.domain.User;
import com.sansfile.app.domain.enumeration.NotificationType;
import com.sansfile.app.domain.enumeration.OrderStatus;
import com.sansfile.app.domain.enumeration.OrderType;
import com.sansfile.app.domain.enumeration.RecipientRole;
import com.sansfile.app.repository.AppNotificationRepository;
import com.sansfile.app.repository.BoutiqueOrderRepository;
import com.sansfile.app.repository.OrderItemRepository;
import com.sansfile.app.repository.ProductRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.service.custom.locality.LocalityException;
import com.sansfile.app.service.custom.locality.LocalityService;
import com.sansfile.app.service.custom.locality.PartnerService;
import com.sansfile.app.service.custom.order.OrderCustomService;
import com.sansfile.app.service.custom.push.BrowserPushService;
import com.sansfile.app.service.custom.realtime.RealtimeEventService;
import com.sansfile.app.service.dto.BoutiqueOrderDTO;
import com.sansfile.app.service.mapper.AppNotificationMapper;
import com.sansfile.app.service.mapper.BoutiqueOrderMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implémentation du service métier pour les commandes e-commerce de la boutique SansFile.
 *
 * Cycle de vie d'une commande :
 *  - Client : checkout -> EN_ATTENTE (en attente de confirmation WhatsApp / appel)
 *  - Confirmation (client via WhatsApp, ou admin) -> EN_COURS
 *  - Admin : EN_COURS -> LIVRE / ANNULE
 *  - Admin peut créer directement une commande (client au téléphone) en EN_ATTENTE ou EN_COURS.
 * Chaque transition est diffusée en temps réel (ORDER_CREATED / ORDER_UPDATED).
 */
@Service
@Transactional
public class OrderCustomServiceImpl implements OrderCustomService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderCustomServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BoutiqueOrderRepository boutiqueOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final BoutiqueOrderMapper boutiqueOrderMapper;
    private final ApplicationProperties applicationProperties;
    private final AppNotificationRepository appNotificationRepository;
    private final AppNotificationMapper appNotificationMapper;
    private final RealtimeEventService realtimeEventService;
    private final BrowserPushService browserPushService;
    private final LocalityService localityService;
    private final PartnerService partnerService;

    public OrderCustomServiceImpl(
        BoutiqueOrderRepository boutiqueOrderRepository,
        OrderItemRepository orderItemRepository,
        ProductRepository productRepository,
        UserRepository userRepository,
        BoutiqueOrderMapper boutiqueOrderMapper,
        ApplicationProperties applicationProperties,
        AppNotificationRepository appNotificationRepository,
        AppNotificationMapper appNotificationMapper,
        RealtimeEventService realtimeEventService,
        BrowserPushService browserPushService,
        LocalityService localityService,
        PartnerService partnerService
    ) {
        this.localityService = localityService;
        this.partnerService = partnerService;
        this.boutiqueOrderRepository = boutiqueOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.boutiqueOrderMapper = boutiqueOrderMapper;
        this.applicationProperties = applicationProperties;
        this.appNotificationRepository = appNotificationRepository;
        this.appNotificationMapper = appNotificationMapper;
        this.realtimeEventService = realtimeEventService;
        this.browserPushService = browserPushService;
    }

    // ──────────────────────────────────────────────────────────────
    // Checkout client
    // ──────────────────────────────────────────────────────────────

    @Override
    public CheckoutResult checkout(CheckoutRequest request, String userLogin) {
        User currentUser = userLogin != null ? userRepository.findOneByLogin(userLogin).orElse(null) : null;

        OrderType type = "CALL".equalsIgnoreCase(request.orderType()) ? OrderType.CALL : OrderType.WHATSAPP;

        BuiltOrder built = buildOrder(
            request.items(),
            request.deliveryAddress(),
            customerLocalityId(currentUser),
            type,
            request.customerName() != null ? request.customerName() : currentUser != null ? currentUser.getFirstName() : "Client",
            request.customerPhone() != null ? request.customerPhone() : currentUser != null ? currentUser.getLogin() : "",
            request.notes(),
            request.latitude(),
            request.longitude(),
            currentUser,
            OrderStatus.EN_ATTENTE
        );

        BoutiqueOrder savedOrder = built.order();
        String whatsAppUrl = buildWhatsAppUrl(savedOrder, built.summaryText());
        BoutiqueOrderDTO orderDTO = toDto(savedOrder);
        orderDTO.setWhatsAppUrl(whatsAppUrl);

        if (currentUser != null) {
            sendInAppNotification(
                currentUser,
                RecipientRole.CLIENT,
                NotificationType.ORDER,
                "Commande reçue 🛍️",
                String.format(
                    "Votre commande #%s (%,d FCFA) est en attente de confirmation. Confirmez-la sur WhatsApp pour lancer la préparation.",
                    savedOrder.getOrderNumber(),
                    savedOrder.getTotalPrice()
                ),
                "/client/boutique/commandes"
            );
        }

        realtimeEventService.broadcast("ORDER_CREATED", orderDTO);
        LOG.info(
            "🛍️ Commande #{} créée en attente à {} (Total: {} FCFA, acompte {} FCFA)",
            savedOrder.getOrderNumber(),
            savedOrder.getDeliveryDistrict(),
            savedOrder.getTotalPrice(),
            savedOrder.getUpfrontAmount()
        );

        return new CheckoutResult(
            savedOrder.getId(),
            savedOrder.getOrderNumber(),
            savedOrder.getSubtotal(),
            savedOrder.getDeliveryFee(),
            savedOrder.getTotalPrice(),
            savedOrder.getUpfrontAmount(),
            savedOrder.getPartnerAmount(),
            savedOrder.getDeliveryDistrict(),
            savedOrder.getStatus().name(),
            savedOrder.getOrderType().name(),
            whatsAppUrl,
            forCustomer(orderDTO)
        );
    }

    // ──────────────────────────────────────────────────────────────
    // Création admin (client au téléphone)
    // ──────────────────────────────────────────────────────────────

    @Override
    public BoutiqueOrderDTO adminCreateOrder(AdminCreateOrderRequest request, String adminLogin) {
        OrderType type = "WHATSAPP".equalsIgnoreCase(request.orderType()) ? OrderType.WHATSAPP : OrderType.CALL;
        OrderStatus status = parseStatus(request.status(), OrderStatus.EN_COURS);
        if (status != OrderStatus.EN_ATTENTE && status != OrderStatus.EN_COURS) {
            status = OrderStatus.EN_COURS;
        }

        // rattache la commande à un compte client existant si le numéro correspond
        User linkedUser =
            request.customerPhone() != null ? userRepository.findOneByLogin(request.customerPhone().trim()).orElse(null) : null;

        BuiltOrder built = buildOrder(
            request.items(),
            request.deliveryAddress(),
            resolveLocalityId(request.localityId(), linkedUser),
            type,
            request.customerName(),
            request.customerPhone(),
            request.notes(),
            null,
            null,
            linkedUser,
            status
        );

        BoutiqueOrder savedOrder = built.order();
        // Commande saisie déjà confirmée (acompte reçu) : son stock est retiré tout de suite
        applyStock(savedOrder, null, savedOrder.getStatus());
        savedOrder = boutiqueOrderRepository.save(savedOrder);
        BoutiqueOrderDTO orderDTO = toDto(savedOrder);

        if (linkedUser != null) {
            sendInAppNotification(
                linkedUser,
                RecipientRole.CLIENT,
                NotificationType.ORDER,
                "Commande enregistrée 🛍️",
                String.format(
                    "Une commande #%s (%,d FCFA) a été enregistrée pour vous par l'équipe SansFile.",
                    savedOrder.getOrderNumber(),
                    savedOrder.getTotalPrice()
                ),
                "/client/boutique/commandes"
            );
        }

        realtimeEventService.broadcast("ORDER_CREATED", orderDTO);
        LOG.info(
            "🛍️ Commande admin #{} créée pour {} ({}) - statut {}",
            savedOrder.getOrderNumber(),
            savedOrder.getCustomerName(),
            savedOrder.getCustomerPhone(),
            status
        );
        return orderDTO;
    }

    // ──────────────────────────────────────────────────────────────
    // Confirmation / changement de statut
    // ──────────────────────────────────────────────────────────────

    @Override
    public BoutiqueOrderDTO confirmOrder(Long orderId, String actorLogin) {
        BoutiqueOrder order = boutiqueOrderRepository
            .findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Commande introuvable ID : " + orderId));

        if (order.getStatus() == OrderStatus.EN_ATTENTE) {
            // Acompte reçu : les articles sortent du stock du partenaire (refus si un article manque)
            applyStock(order, OrderStatus.EN_ATTENTE, OrderStatus.EN_COURS);
            order.setStatus(OrderStatus.EN_COURS);
            order.setLastModifiedDate(Instant.now());
            order = boutiqueOrderRepository.save(order);

            notifyStatusChange(order);
            BoutiqueOrderDTO dto = toDto(order);
            realtimeEventService.broadcast("ORDER_UPDATED", dto);
            LOG.info("📦 Commande #{} confirmée (EN_COURS)", order.getOrderNumber());
            return dto;
        }
        return toDto(order);
    }

    @Override
    public BoutiqueOrderDTO updateOrderStatus(Long orderId, String status) {
        BoutiqueOrder order = boutiqueOrderRepository
            .findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Commande introuvable ID : " + orderId));

        OrderStatus newStatus = parseStatus(status, null);
        if (newStatus == null) {
            throw new IllegalArgumentException("Statut de commande invalide : " + status);
        }
        // Livrée : statut définitif (stock, paiement du livreur et facture n'ont plus à bouger)
        if (order.getStatus() == OrderStatus.LIVRE && newStatus != OrderStatus.LIVRE) {
            throw LocalityException.conflict(
                "order-delivered",
                "La commande #" + order.getOrderNumber() + " est livrée : son statut ne peut plus être modifié."
            );
        }

        applyStock(order, order.getStatus(), newStatus);
        order.setStatus(newStatus);
        order.setLastModifiedDate(Instant.now());
        BoutiqueOrder saved = boutiqueOrderRepository.save(order);

        notifyStatusChange(saved);
        BoutiqueOrderDTO dto = toDto(saved);
        realtimeEventService.broadcast("ORDER_UPDATED", dto);
        LOG.info("📦 Statut de la commande #{} changé en {}", saved.getOrderNumber(), newStatus);
        return dto;
    }

    // ──────────────────────────────────────────────────────────────
    // Lecture
    // ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BoutiqueOrderDTO> getMyOrders(String userLogin) {
        if (userLogin == null) {
            return Collections.emptyList();
        }
        User user = userRepository.findOneByLogin(userLogin).orElse(null);
        if (user == null) {
            return Collections.emptyList();
        }
        return boutiqueOrderRepository
            .findByUserIdOrderByCreatedDateDesc(user.getId())
            .stream()
            .map(this::toDto)
            .map(OrderCustomServiceImpl::forCustomer)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public QuoteResult quote(QuoteRequest request, String userLogin) {
        User user = userLogin != null ? userRepository.findOneByLogin(userLogin).orElse(null) : null;
        Locality locality = localityService.requireActive(customerLocalityId(user));
        Optional<Partner> partner = partnerService.activePartnerOf(locality.getId());
        long deliveryFee = locality.getDeliveryFee() == null ? 0L : locality.getDeliveryFee();
        List<CartItemRequest> items = request.items() == null ? List.of() : request.items();
        if (partner.isEmpty()) {
            List<Long> all = items.stream().map(CartItemRequest::productId).toList();
            return new QuoteResult(
                locality.getId(),
                locality.getName(),
                false,
                0,
                deliveryFee,
                deliveryFee,
                deliveryFee,
                0,
                all,
                List.of()
            );
        }

        long subtotal = 0L;
        long partnerAmount = 0L;
        List<Long> unavailable = new ArrayList<>();
        List<StockShortage> shortages = new ArrayList<>();
        Map<Long, Integer> wanted = quantities(items);
        for (CartItemRequest item : items) {
            if (item == null || item.productId() == null) {
                continue;
            }
            Optional<Product> product = productRepository.findById(item.productId()).filter(p -> !Boolean.FALSE.equals(p.getInStock()));
            Optional<PartnerProduct> offer = product.flatMap(p -> partnerService.availableOffer(partner.get(), p.getId()));
            if (product.isEmpty() || offer.isEmpty()) {
                unavailable.add(item.productId());
                continue;
            }
            int qty = Math.max(1, item.quantity() == null ? 1 : item.quantity());
            int stock = offer.get().getStockQuantity();
            if (
                stock < wanted.getOrDefault(item.productId(), qty) &&
                shortages.stream().noneMatch(s -> s.productId().equals(item.productId()))
            ) {
                shortages.add(new StockShortage(item.productId(), stock));
            }
            long price = product.get().getPrice();
            subtotal += price * qty;
            partnerAmount += Math.min(offer.get().getWholesalePrice(), price) * qty;
        }
        return new QuoteResult(
            locality.getId(),
            locality.getName(),
            true,
            subtotal,
            deliveryFee,
            subtotal + deliveryFee,
            subtotal - partnerAmount + deliveryFee,
            partnerAmount,
            unavailable,
            shortages
        );
    }

    @Override
    public BoutiqueOrderDTO updateCourier(Long orderId, CourierUpdate update) {
        BoutiqueOrder order = boutiqueOrderRepository
            .findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Commande introuvable ID : " + orderId));
        if (update.courierName() != null) {
            String name = update.courierName().trim();
            order.setCourierName(name.isEmpty() ? null : truncate(name, 100));
        }
        if (update.courierPhone() != null) {
            String phone = update.courierPhone().replaceAll("[^0-9+]", "");
            order.setCourierPhone(phone.isEmpty() ? null : truncate(phone, 30));
        }
        if (update.courierPaid() != null) {
            order.setCourierPaid(update.courierPaid());
        }
        order.setLastModifiedDate(Instant.now());
        BoutiqueOrderDTO dto = toDto(boutiqueOrderRepository.save(order));
        realtimeEventService.broadcast("ORDER_UPDATED", dto);
        return dto;
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private record BuiltOrder(BoutiqueOrder order, String summaryText) {}

    /**
     * Construit la commande pour le partenaire de la localité : chaque article doit être disponible chez
     * lui. Montants : part du partenaire = prix de gros × quantités (payée à son livreur à la réception) ;
     * acompte = part SansFile (prix de vente − prix de gros) + frais de livraison de la localité.
     */
    private BuiltOrder buildOrder(
        List<CartItemRequest> itemRequests,
        String deliveryAddress,
        Long localityId,
        OrderType type,
        String customerName,
        String customerPhone,
        String notes,
        Double latitude,
        Double longitude,
        User user,
        OrderStatus status
    ) {
        if (itemRequests == null || itemRequests.isEmpty()) {
            throw new IllegalArgumentException("Le panier est vide.");
        }

        Locality locality = localityService.requireActive(localityId);
        Partner partner = partnerService
            .activePartnerOf(locality.getId())
            .orElseThrow(() ->
                LocalityException.invalid(
                    "shop-unavailable",
                    "La boutique n'est pas encore disponible à " + locality.getName() + ". Revenez bientôt !"
                )
            );

        long subtotal = 0L;
        long partnerAmount = 0L;
        List<OrderItem> orderItems = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        Map<Long, Integer> wanted = quantities(itemRequests);

        for (CartItemRequest itemReq : itemRequests) {
            Product product = productRepository
                .findById(itemReq.productId())
                .orElseThrow(() -> new IllegalArgumentException("Produit introuvable ID : " + itemReq.productId()));

            if (Boolean.FALSE.equals(product.getInStock())) {
                throw new IllegalStateException("Le produit \"" + product.getTitle() + "\" est en rupture de stock.");
            }
            PartnerProduct offer = partnerService
                .availableOffer(partner, product.getId())
                .orElseThrow(() ->
                    LocalityException.conflict(
                        "product-unavailable",
                        "« " +
                            product.getTitle() +
                            " » n'est pas disponible à " +
                            locality.getName() +
                            " pour le moment. Retirez-le du panier pour continuer."
                    )
                );

            int qty = Math.max(1, itemReq.quantity());
            // Le stock n'est retiré qu'à la réception de l'acompte, mais on ne vend pas ce qui manque déjà
            if (offer.getStockQuantity() < wanted.getOrDefault(product.getId(), qty)) {
                throw LocalityException.conflict(
                    "stock-insufficient",
                    String.format(
                        "Il ne reste que %d « %s » à %s. Réduisez la quantité pour continuer.",
                        offer.getStockQuantity(),
                        product.getTitle(),
                        locality.getName()
                    )
                );
            }
            long itemTotal = product.getPrice() * qty;
            // Le prix de gros ne dépasse jamais le prix de vente (contrôlé à la saisie) : garde-fou
            long wholesale = Math.min(offer.getWholesalePrice(), product.getPrice());
            subtotal += itemTotal;
            partnerAmount += wholesale * qty;

            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(product);
            orderItem.setQuantity(qty);
            orderItem.setUnitPrice(product.getPrice());
            orderItem.setWholesaleUnitPrice(wholesale);
            orderItem.setProductTitle(product.getTitle());
            orderItems.add(orderItem);

            summary.append(String.format("- %s (x%d) : %,d FCFA%n", product.getTitle(), qty, itemTotal));
        }

        long deliveryFee = locality.getDeliveryFee() == null ? 0L : locality.getDeliveryFee();
        long totalPrice = subtotal + deliveryFee;
        String orderNumber = generateOrderNumber();

        BoutiqueOrder order = new BoutiqueOrder();
        order.setOrderNumber(orderNumber);
        order.setSubtotal(subtotal);
        order.setDeliveryFee(deliveryFee);
        order.setTotalPrice(totalPrice);
        order.setPartnerAmount(partnerAmount);
        order.setUpfrontAmount(subtotal - partnerAmount + deliveryFee);
        order.setStatus(status);
        order.setOrderType(type);
        order.setDeliveryAddress(deliveryAddress);
        order.setDeliveryDistrict(locality.getName());
        order.setLocalityId(locality.getId());
        order.setPartnerId(partner.getId());
        order.setPartnerName(partner.getName());
        order.setPartnerPhone(partner.getPhone());
        order.setCourierName(partner.getCourierName());
        order.setCourierPhone(partner.getCourierPhone());
        order.setCourierPaid(false);
        order.setDeliveryLatitude(validCoordinate(latitude, 90));
        order.setDeliveryLongitude(validCoordinate(longitude, 180));
        order.setCustomerName(customerName != null && !customerName.isBlank() ? customerName.trim() : "Client");
        order.setCustomerPhone(customerPhone != null ? customerPhone.trim() : "");
        order.setNotes(notes);
        order.setUser(user);
        order.setCreatedDate(Instant.now());
        order.setStockDeducted(false);
        order.setInvoiceToken(newInvoiceToken());

        BoutiqueOrder savedOrder = boutiqueOrderRepository.save(order);
        for (OrderItem oi : orderItems) {
            oi.setOrder(savedOrder);
            orderItemRepository.save(oi);
        }
        savedOrder.setItemses(new java.util.HashSet<>(orderItems));

        return new BuiltOrder(savedOrder, summary.toString());
    }

    /** Lien de facture : 128 bits aléatoires, impossible à deviner. */
    private static String newInvoiceToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Quantités par produit d'un panier (un même article sur deux lignes compte une seule fois). */
    private static Map<Long, Integer> quantities(List<CartItemRequest> items) {
        Map<Long, Integer> wanted = new LinkedHashMap<>();
        for (CartItemRequest item : items) {
            if (item != null && item.productId() != null) {
                wanted.merge(item.productId(), Math.max(1, item.quantity() == null ? 1 : item.quantity()), Integer::sum);
            }
        }
        return wanted;
    }

    private static boolean holdsStock(OrderStatus status) {
        return status == OrderStatus.EN_COURS || status == OrderStatus.LIVRE;
    }

    /**
     * Stock du partenaire selon le statut : acompte reçu (en cours ou livrée) → articles retirés du stock ;
     * annulée ou remise en attente → articles remis en stock. Une commande confirmée avant la gestion du
     * stock (rien de retiré) n'est jamais déduite en passant d'« en cours » à « livrée ». Appelé avant de
     * changer le statut : en cas de refus (stock insuffisant), la commande reste telle quelle.
     */
    private void applyStock(BoutiqueOrder order, OrderStatus previous, OrderStatus next) {
        if (order.getPartnerId() == null) {
            return;
        }
        boolean deducted = Boolean.TRUE.equals(order.getStockDeducted());
        if (holdsStock(next) && !deducted && (previous == null || !holdsStock(previous))) {
            partnerService.deductStock(order.getPartnerId(), orderedQuantities(order));
            order.setStockDeducted(true);
        } else if (!holdsStock(next) && deducted) {
            partnerService.restoreStock(order.getPartnerId(), orderedQuantities(order));
            order.setStockDeducted(false);
        }
    }

    private Map<Long, Integer> orderedQuantities(BoutiqueOrder order) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
            if (item.getProduct() != null && item.getQuantity() != null) {
                quantities.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum);
            }
        }
        return quantities;
    }

    private String generateOrderNumber() {
        for (int attempt = 0; attempt < 6; attempt++) {
            String candidate = "CMD-2026-" + (1000 + RANDOM.nextInt(9000));
            if (boutiqueOrderRepository.findByOrderNumber(candidate).isEmpty()) {
                return candidate;
            }
        }
        return "CMD-2026-" + (System.currentTimeMillis() % 1000000);
    }

    private String buildWhatsAppUrl(BoutiqueOrder order, String summaryText) {
        String whatsappPhone = applicationProperties.getBusiness().getWhatsappNumber();
        String messageTemplate = String.format(
            "👋 Bonjour SansFile ! Je souhaite confirmer ma commande *#%s* :%n%n" +
                "📦 *Articles :*%n%s%n" +
                "💰 *Sous-total :* %,d FCFA%n" +
                "🛵 *Livraison :* %,d FCFA%n" +
                "💵 *TOTAL :* %,d FCFA%n%n" +
                "💳 *À envoyer maintenant (Wave / Orange Money) :* %,d FCFA%n" +
                "🤝 *À payer au livreur à la réception :* %,d FCFA%n%n" +
                "📍 *Livraison à :* %s (%s)%n" +
                "%s" +
                "👤 *Nom :* %s%n" +
                "📞 *Téléphone :* %s",
            order.getOrderNumber(),
            summaryText,
            order.getSubtotal(),
            order.getDeliveryFee(),
            order.getTotalPrice(),
            order.getUpfrontAmount(),
            order.getPartnerAmount(),
            order.getDeliveryDistrict(),
            order.getDeliveryAddress() != null ? order.getDeliveryAddress() : "adresse à préciser",
            order.getDeliveryLatitude() != null && order.getDeliveryLongitude() != null
                ? String.format(
                      java.util.Locale.ROOT,
                      "🗺️ *Position :* https://maps.google.com/?q=%.6f,%.6f%n",
                      order.getDeliveryLatitude(),
                      order.getDeliveryLongitude()
                  )
                : "",
            order.getCustomerName(),
            order.getCustomerPhone()
        );
        return "https://wa.me/" + whatsappPhone + "?text=" + URLEncoder.encode(messageTemplate, StandardCharsets.UTF_8);
    }

    /** Commande saisie par l'administration : la localité choisie, sinon celle du compte du client. */
    private Long resolveLocalityId(Long requested, User user) {
        return requested != null ? requested : customerLocalityId(user);
    }

    /** Commande d'un client ou d'un coiffeur : toujours la localité de son compte, jamais une autre. */
    private Long customerLocalityId(User user) {
        Long fromAccount = user != null ? localityService.accountLocality(user).localityId() : null;
        if (fromAccount == null) {
            throw LocalityException.invalid("locality-required", "Choisissez votre localité pour commander.");
        }
        return fromAccount;
    }

    /** Réponse au client : sans l'identité du partenaire ni le paiement du livreur (affaires internes). */
    private static BoutiqueOrderDTO forCustomer(BoutiqueOrderDTO dto) {
        dto.setPartnerId(null);
        dto.setPartnerName(null);
        dto.setPartnerPhone(null);
        dto.setCourierPaid(null);
        // La facture du partenaire montre ses prix de gros : jamais au client
        dto.setInvoiceToken(null);
        dto.setStockDeducted(null);
        // Le livreur n'est indiqué au client qu'une fois la commande confirmée (en cours de livraison)
        if (dto.getStatus() != OrderStatus.EN_COURS) {
            dto.setCourierName(null);
            dto.setCourierPhone(null);
        }
        return dto;
    }

    private static Double validCoordinate(Double value, double max) {
        return value != null && Double.isFinite(value) && Math.abs(value) <= max ? value : null;
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private OrderStatus parseStatus(String raw, OrderStatus fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return OrderStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private BoutiqueOrderDTO toDto(BoutiqueOrder order) {
        BoutiqueOrderDTO dto = boutiqueOrderMapper.toDto(order);
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            List<OrderItem> lines = orderItemRepository.findByOrderId(order.getId());
            dto.setItems(
                lines
                    .stream()
                    .sorted(java.util.Comparator.comparing(i -> i.getId() == null ? Long.MAX_VALUE : i.getId()))
                    .map(BoutiqueOrderMapper::toOrderLine)
                    .toList()
            );
        }
        return dto;
    }

    private void notifyStatusChange(BoutiqueOrder order) {
        if (order.getUser() == null) {
            return;
        }
        String statusLabel = switch (order.getStatus()) {
            case EN_ATTENTE -> "en attente de confirmation";
            case EN_COURS -> "confirmée et en préparation ✅";
            case LIVRE -> "livrée avec succès 🎉";
            case ANNULE -> "annulée";
        };
        sendInAppNotification(
            order.getUser(),
            RecipientRole.CLIENT,
            NotificationType.ORDER,
            "Suivi Commande 📦",
            String.format("Votre commande #%s est désormais %s.", order.getOrderNumber(), statusLabel),
            "/client/boutique/commandes"
        );
    }

    private void sendInAppNotification(
        User user,
        RecipientRole role,
        NotificationType type,
        String title,
        String message,
        String targetRoute
    ) {
        try {
            AppNotification notif = new AppNotification();
            notif.setUser(user);
            notif.setRecipientRole(role);
            notif.setType(type);
            notif.setTitle(title);
            notif.setMessage(message);
            notif.setIsRead(false);
            notif.setTargetRoute(targetRoute);
            notif.setCreatedDate(Instant.now());
            AppNotification saved = appNotificationRepository.save(notif);
            realtimeEventService.broadcast("NOTIFICATION_CREATED", appNotificationMapper.toDto(saved));
            browserPushService.sendToUser(user, saved);
            LOG.info("🔔 Notification In-App commande créée : {}", title);
        } catch (Exception e) {
            LOG.warn("⚠️ Erreur création notification in-app commande : {}", e.getMessage());
        }
    }
}
