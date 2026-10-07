package com.sansfile.app.service.custom.order;

import com.sansfile.app.domain.BoutiqueOrder;
import com.sansfile.app.domain.OrderItem;
import com.sansfile.app.domain.Product;
import com.sansfile.app.domain.ProductImage;
import com.sansfile.app.repository.BoutiqueOrderRepository;
import com.sansfile.app.repository.OrderItemRepository;
import com.sansfile.app.repository.ProductImageRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * Facture envoyée au partenaire pour une commande : les articles avec leurs photos (pour préparer sans
 * erreur), le client à livrer et le montant que son livreur encaisse. Ouverte sans connexion par un lien
 * impossible à deviner. Elle ne montre que les prix du partenaire (prix de gros), jamais les prix de vente
 * ni la part SansFile.
 */
@Service
@Transactional(readOnly = true)
public class PartnerInvoiceService {

    private static final Logger LOG = LoggerFactory.getLogger(PartnerInvoiceService.class);

    private static final Pattern TOKEN = Pattern.compile("^[0-9a-f]{32}$");
    private static final ZoneId DAKAR = ZoneId.of("Africa/Dakar");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'mm", Locale.FRANCE).withZone(DAKAR);

    /** Ligne de la facture : {@code unitPrice} et {@code total} absents pour une commande d'avant les partenaires. */
    public record InvoiceLine(String title, String brand, String image, int quantity, String unitPrice, String total) {}

    /** {@code status} : EN_ATTENTE, EN_COURS, LIVRE ou ANNULE ; {@code amountToCollect} absent sans prix de gros. */
    public record Invoice(
        String orderNumber,
        String date,
        String status,
        String partnerName,
        String partnerPhone,
        String localityName,
        String customerName,
        String customerPhone,
        String deliveryAddress,
        String mapsUrl,
        String courierName,
        String courierPhone,
        List<InvoiceLine> lines,
        int articleCount,
        String amountToCollect
    ) {}

    private final BoutiqueOrderRepository boutiqueOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductImageRepository productImageRepository;
    private final SpringTemplateEngine templateEngine;
    private final String logo;

    public PartnerInvoiceService(
        BoutiqueOrderRepository boutiqueOrderRepository,
        OrderItemRepository orderItemRepository,
        ProductImageRepository productImageRepository,
        SpringTemplateEngine templateEngine
    ) {
        this.boutiqueOrderRepository = boutiqueOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productImageRepository = productImageRepository;
        this.templateEngine = templateEngine;
        this.logo = loadLogo();
    }

    /** Facture de ce lien, ou rien si le lien est inconnu. */
    public Optional<Invoice> find(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return boutiqueOrderRepository.findOneByInvoiceToken(token).map(this::toInvoice);
    }

    /** Page HTML de la facture ; {@code nonce} autorise le seul script de la page (bouton Imprimer / PDF). */
    public String render(Invoice invoice, String nonce) {
        Context context = new Context(Locale.FRANCE);
        context.setVariable("invoice", invoice);
        context.setVariable("logo", logo);
        context.setVariable("nonce", nonce);
        return templateEngine.process("invoice/partner-invoice", context);
    }

    private Invoice toInvoice(BoutiqueOrder order) {
        List<OrderItem> items = orderItemRepository
            .findByOrderId(order.getId())
            .stream()
            .sorted(Comparator.comparing(i -> i.getId() == null ? Long.MAX_VALUE : i.getId()))
            .toList();
        boolean pricesKnown = order.getPartnerAmount() != null && items.stream().allMatch(i -> i.getWholesaleUnitPrice() != null);
        List<InvoiceLine> lines = items
            .stream()
            .map(item -> toLine(item, pricesKnown))
            .toList();
        String maps =
            order.getDeliveryLatitude() != null && order.getDeliveryLongitude() != null
                ? String.format(
                      Locale.ROOT,
                      "https://maps.google.com/?q=%.6f,%.6f",
                      order.getDeliveryLatitude(),
                      order.getDeliveryLongitude()
                  )
                : null;
        return new Invoice(
            order.getOrderNumber(),
            order.getCreatedDate() != null ? DATE.format(order.getCreatedDate()) : "",
            order.getStatus() != null ? order.getStatus().name() : "EN_ATTENTE",
            order.getPartnerName(),
            order.getPartnerPhone(),
            order.getDeliveryDistrict(),
            order.getCustomerName(),
            order.getCustomerPhone(),
            order.getDeliveryAddress(),
            maps,
            order.getCourierName(),
            order.getCourierPhone(),
            lines,
            items
                .stream()
                .mapToInt(i -> i.getQuantity() == null ? 0 : i.getQuantity())
                .sum(),
            pricesKnown ? money(order.getPartnerAmount()) : null
        );
    }

    private InvoiceLine toLine(OrderItem item, boolean pricesKnown) {
        Product product = item.getProduct();
        int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
        String title = item.getProductTitle() != null ? item.getProductTitle() : product != null ? product.getTitle() : "Article";
        Long unit = item.getWholesaleUnitPrice();
        return new InvoiceLine(
            title,
            product != null ? product.getBrand() : null,
            product != null ? photoOf(product.getId()) : null,
            quantity,
            pricesKnown ? money(unit) : null,
            pricesKnown ? money(unit * quantity) : null
        );
    }

    /** Première photo du produit (adresse web uniquement : rien d'autre n'entre dans la page). */
    private String photoOf(Long productId) {
        return productImageRepository
            .findByProductIdOrderBySortOrderAsc(productId)
            .stream()
            .map(ProductImage::getImageUrl)
            .filter(url -> url != null && (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("/")))
            .findFirst()
            .orElse(null);
    }

    private static String money(Long amount) {
        return String.format(Locale.FRANCE, "%,d FCFA", amount == null ? 0L : amount);
    }

    private static String loadLogo() {
        try (InputStream in = new ClassPathResource("templates/invoice/sansfile-icon.png").getInputStream()) {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (IOException e) {
            LOG.warn("Logo de la facture introuvable : {}", e.getMessage());
            return null;
        }
    }
}
