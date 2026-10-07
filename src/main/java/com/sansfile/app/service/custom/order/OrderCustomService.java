package com.sansfile.app.service.custom.order;

import com.sansfile.app.service.dto.BoutiqueOrderDTO;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Interface de contrat pour les commandes e-commerce de la boutique SansFile.
 */
public interface OrderCustomService {
    record CartItemRequest(@NotNull Long productId, @NotNull Integer quantity) {}

    /**
     * Commande client : livrée dans la localité du compte (celle du salon pour un coiffeur) et attribuée
     * au partenaire de cette localité. Une localité envoyée par l'application est ignorée.
     */
    record CheckoutRequest(
        @NotEmpty List<CartItemRequest> items,
        String deliveryAddress,
        String deliveryDistrict,
        String orderType, // "WHATSAPP" ou "CALL"
        String customerName,
        String customerPhone,
        String notes,
        Double latitude,
        Double longitude
    ) {}

    record AdminCreateOrderRequest(
        @NotEmpty List<CartItemRequest> items,
        String deliveryAddress,
        String deliveryDistrict,
        String orderType,
        @NotNull String customerName,
        @NotNull String customerPhone,
        String notes,
        String status, // optionnel : EN_ATTENTE (défaut) ou EN_COURS
        Long localityId
    ) {}

    /**
     * {@code upfrontAmount} : à envoyer avant confirmation (part SansFile + livraison) ;
     * {@code partnerAmount} : à payer au livreur à la réception.
     */
    record CheckoutResult(
        Long id,
        String orderNumber,
        Long subtotal,
        Long deliveryFee,
        Long totalPrice,
        Long upfrontAmount,
        Long partnerAmount,
        String localityName,
        String status,
        String orderType,
        String whatsAppUrl,
        BoutiqueOrderDTO order
    ) {}

    /** Livreur du partenaire pour une commande, et paiement de sa livraison par SansFile. */
    record CourierUpdate(String courierName, String courierPhone, Boolean courierPaid) {}

    /** Panier à chiffrer dans la localité du compte. */
    record QuoteRequest(List<CartItemRequest> items) {}

    /** Article demandé en plus grande quantité que le stock du partenaire ({@code remaining} : ce qui reste). */
    record StockShortage(Long productId, int remaining) {}

    /**
     * Montants d'un panier dans une localité, sans créer de commande. {@code unavailableProductIds} :
     * articles que le partenaire n'a pas (exclus des montants) ; {@code stockShortages} : articles dont il
     * n'a pas assez ; {@code shopAvailable} : boutique ouverte.
     */
    record QuoteResult(
        Long localityId,
        String localityName,
        boolean shopAvailable,
        long subtotal,
        long deliveryFee,
        long totalPrice,
        long upfrontAmount,
        long partnerAmount,
        List<Long> unavailableProductIds,
        List<StockShortage> stockShortages
    ) {}

    /** Devis du panier : ce que le client enverra avant confirmation et ce qu'il paiera au livreur. */
    QuoteResult quote(QuoteRequest request, String userLogin);

    /** Livreur de la commande (pré-rempli avec le livreur habituel du partenaire) et paiement de la livraison. */
    BoutiqueOrderDTO updateCourier(Long orderId, CourierUpdate update);

    /**
     * Enregistre une commande client en statut EN_ATTENTE et génère le lien de confirmation WhatsApp.
     */
    CheckoutResult checkout(CheckoutRequest request, String userLogin);

    /**
     * Création d'une commande par l'admin pour un client qui appelle / passe commande hors application.
     */
    BoutiqueOrderDTO adminCreateOrder(AdminCreateOrderRequest request, String adminLogin);

    /**
     * Confirme une commande EN_ATTENTE (passage en EN_COURS). Idempotent si déjà confirmée.
     */
    BoutiqueOrderDTO confirmOrder(Long orderId, String actorLogin);

    /**
     * Récupère l'historique des commandes d'un client.
     */
    List<BoutiqueOrderDTO> getMyOrders(String userLogin);

    /**
     * Met à jour le statut d'une commande (admin).
     */
    BoutiqueOrderDTO updateOrderStatus(Long orderId, String status);
}
