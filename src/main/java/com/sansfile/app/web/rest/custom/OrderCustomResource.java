package com.sansfile.app.web.rest.custom;

import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.security.SecurityUtils;
import com.sansfile.app.service.custom.locality.LocalityException;
import com.sansfile.app.service.custom.order.OrderCustomService;
import com.sansfile.app.service.custom.order.OrderCustomService.AdminCreateOrderRequest;
import com.sansfile.app.service.custom.order.OrderCustomService.CheckoutRequest;
import com.sansfile.app.service.custom.order.OrderCustomService.CheckoutResult;
import com.sansfile.app.service.custom.order.OrderCustomService.CourierUpdate;
import com.sansfile.app.service.custom.order.OrderCustomService.QuoteRequest;
import com.sansfile.app.service.custom.order.OrderCustomService.QuoteResult;
import com.sansfile.app.service.dto.BoutiqueOrderDTO;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Contrôleur REST métier pour les commandes e-commerce de la boutique SansFile.
 */
@Tag(name = "6. Boutique & Commandes", description = "Gestion du panier et validation des commandes")
@RestController
@RequestMapping("/api")
public class OrderCustomResource {

    private static final Logger LOG = LoggerFactory.getLogger(OrderCustomResource.class);

    private final OrderCustomService orderCustomService;

    public OrderCustomResource(OrderCustomService orderCustomService) {
        this.orderCustomService = orderCustomService;
    }

    public record UpdateStatusRequest(@NotNull String status) {}

    /**
     * POST /api/orders/checkout : Enregistre une commande client (statut EN_ATTENTE) et génère le lien WhatsApp.
     */
    @PostMapping("/orders/checkout")
    public ResponseEntity<?> checkout(@Valid @RequestBody CheckoutRequest request) {
        try {
            String currentLogin = SecurityUtils.getCurrentUserLogin().orElse(null);
            CheckoutResult result = orderCustomService.checkout(request, currentLogin);
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (LocalityException e) {
            // Boutique fermée dans la localité, produit indisponible… : message et code pour le client
            throw e;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("Erreur lors de la création de la commande", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Impossible d'enregistrer la commande."));
        }
    }

    /**
     * POST /api/orders/quote : montants du panier dans la localité du compte (acompte, part payée au livreur,
     * articles indisponibles chez le partenaire), sans créer de commande.
     */
    @PostMapping("/orders/quote")
    public QuoteResult quote(@RequestBody QuoteRequest request) {
        return orderCustomService.quote(request, SecurityUtils.getCurrentUserLogin().orElse(null));
    }

    /**
     * POST /api/orders/admin-create : L'admin enregistre une commande pour un client qui appelle.
     */
    @PostMapping("/orders/admin-create")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<?> adminCreateOrder(@Valid @RequestBody AdminCreateOrderRequest request) {
        try {
            String adminLogin = SecurityUtils.getCurrentUserLogin().orElse(null);
            BoutiqueOrderDTO dto = orderCustomService.adminCreateOrder(request, adminLogin);
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (LocalityException e) {
            throw e;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("Erreur création commande admin", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Impossible d'enregistrer la commande."));
        }
    }

    /**
     * POST /api/orders/{id}/confirm : Confirme une commande EN_ATTENTE (client ou admin).
     */
    @PostMapping("/orders/{id}/confirm")
    public ResponseEntity<?> confirmOrder(@PathVariable Long id) {
        try {
            String actorLogin = SecurityUtils.getCurrentUserLogin().orElse(null);
            return ResponseEntity.ok(orderCustomService.confirmOrder(id, actorLogin));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/orders/my-orders : Récupère l'historique des commandes du client connecté.
     */
    @GetMapping("/orders/my-orders")
    public ResponseEntity<List<BoutiqueOrderDTO>> getMyOrders() {
        String currentLogin = SecurityUtils.getCurrentUserLogin().orElse(null);
        if (currentLogin == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(orderCustomService.getMyOrders(currentLogin));
    }

    /**
     * PATCH /api/orders/{id}/status : Met à jour le statut d'une commande (admin).
     */
    @PatchMapping("/orders/{id}/status")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<?> updateOrderStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request) {
        try {
            BoutiqueOrderDTO dto = orderCustomService.updateOrderStatus(id, request.status());
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PATCH /api/orders/{id}/courier : livreur du partenaire pour cette commande et paiement de sa
     * livraison par SansFile (admin).
     */
    @PatchMapping("/orders/{id}/courier")
    @PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")
    public ResponseEntity<?> updateCourier(@PathVariable Long id, @RequestBody CourierUpdate request) {
        try {
            return ResponseEntity.ok(orderCustomService.updateCourier(id, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
