package com.sansfile.app.service.dto;

import com.sansfile.app.domain.enumeration.OrderStatus;
import com.sansfile.app.domain.enumeration.OrderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Lob;
import jakarta.validation.constraints.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A DTO for the {@link com.sansfile.app.domain.BoutiqueOrder} entity.
 */
@Schema(description = "Commande passée sur la boutique via WhatsApp ou Appel direct")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class BoutiqueOrderDTO implements Serializable {

    private Long id;

    @NotNull
    @Size(max = 50)
    private String orderNumber;

    @NotNull
    @Min(value = 0L)
    private Long subtotal;

    @NotNull
    @Min(value = 0L)
    private Long deliveryFee;

    @NotNull
    @Min(value = 0L)
    private Long totalPrice;

    @NotNull
    private OrderStatus status;

    @NotNull
    private OrderType orderType;

    @Size(max = 255)
    private String deliveryAddress;

    @Size(max = 100)
    private String deliveryDistrict;

    @Size(max = 100)
    private String customerName;

    @Size(max = 30)
    private String customerPhone;

    @Lob
    private String notes;

    @NotNull
    private Instant createdDate;

    private Instant lastModifiedDate;

    private UserDTO user;

    /** Lignes d'articles de la commande (à plat, sans référence circulaire). */
    private List<OrderLineDTO> items;

    /** Lien WhatsApp pré-rempli pour confirmer la commande (rempli au checkout uniquement). */
    private String whatsAppUrl;

    /** Localité de livraison (son nom est dans {@code deliveryDistrict}). */
    private Long localityId;

    /** Partenaire qui fournit et livre : réservé à l'administration (retiré des réponses aux clients). */
    private Long partnerId;

    private String partnerName;

    private String partnerPhone;

    /** À envoyer par le client avant confirmation : part SansFile + frais de livraison. */
    private Long upfrontAmount;

    /** À payer au livreur à la réception : part du partenaire. */
    private Long partnerAmount;

    private String courierName;

    private String courierPhone;

    /** Livreur payé par SansFile (administration uniquement). */
    private Boolean courierPaid;

    private Double deliveryLatitude;

    private Double deliveryLongitude;

    /** Quantités retirées du stock du partenaire (administration uniquement). */
    private Boolean stockDeducted;

    /** Lien de la facture du partenaire (administration uniquement). */
    private String invoiceToken;

    public Boolean getStockDeducted() {
        return stockDeducted;
    }

    public void setStockDeducted(Boolean stockDeducted) {
        this.stockDeducted = stockDeducted;
    }

    public String getInvoiceToken() {
        return invoiceToken;
    }

    public void setInvoiceToken(String invoiceToken) {
        this.invoiceToken = invoiceToken;
    }

    public Long getLocalityId() {
        return localityId;
    }

    public void setLocalityId(Long localityId) {
        this.localityId = localityId;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public void setPartnerId(Long partnerId) {
        this.partnerId = partnerId;
    }

    public String getPartnerName() {
        return partnerName;
    }

    public void setPartnerName(String partnerName) {
        this.partnerName = partnerName;
    }

    public String getPartnerPhone() {
        return partnerPhone;
    }

    public void setPartnerPhone(String partnerPhone) {
        this.partnerPhone = partnerPhone;
    }

    public Long getUpfrontAmount() {
        return upfrontAmount;
    }

    public void setUpfrontAmount(Long upfrontAmount) {
        this.upfrontAmount = upfrontAmount;
    }

    public Long getPartnerAmount() {
        return partnerAmount;
    }

    public void setPartnerAmount(Long partnerAmount) {
        this.partnerAmount = partnerAmount;
    }

    public String getCourierName() {
        return courierName;
    }

    public void setCourierName(String courierName) {
        this.courierName = courierName;
    }

    public String getCourierPhone() {
        return courierPhone;
    }

    public void setCourierPhone(String courierPhone) {
        this.courierPhone = courierPhone;
    }

    public Boolean getCourierPaid() {
        return courierPaid;
    }

    public void setCourierPaid(Boolean courierPaid) {
        this.courierPaid = courierPaid;
    }

    public Double getDeliveryLatitude() {
        return deliveryLatitude;
    }

    public void setDeliveryLatitude(Double deliveryLatitude) {
        this.deliveryLatitude = deliveryLatitude;
    }

    public Double getDeliveryLongitude() {
        return deliveryLongitude;
    }

    public void setDeliveryLongitude(Double deliveryLongitude) {
        this.deliveryLongitude = deliveryLongitude;
    }

    public List<OrderLineDTO> getItems() {
        return items;
    }

    public void setItems(List<OrderLineDTO> items) {
        this.items = items;
    }

    public String getWhatsAppUrl() {
        return whatsAppUrl;
    }

    public void setWhatsAppUrl(String whatsAppUrl) {
        this.whatsAppUrl = whatsAppUrl;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public Long getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(Long subtotal) {
        this.subtotal = subtotal;
    }

    public Long getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(Long deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public Long getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(Long totalPrice) {
        this.totalPrice = totalPrice;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public void setOrderType(OrderType orderType) {
        this.orderType = orderType;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public void setDeliveryAddress(String deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    public String getDeliveryDistrict() {
        return deliveryDistrict;
    }

    public void setDeliveryDistrict(String deliveryDistrict) {
        this.deliveryDistrict = deliveryDistrict;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    public Instant getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(Instant lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public UserDTO getUser() {
        return user;
    }

    public void setUser(UserDTO user) {
        this.user = user;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BoutiqueOrderDTO)) {
            return false;
        }

        BoutiqueOrderDTO boutiqueOrderDTO = (BoutiqueOrderDTO) o;
        if (this.id == null) {
            return false;
        }
        return Objects.equals(this.id, boutiqueOrderDTO.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.id);
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "BoutiqueOrderDTO{" +
            "id=" + getId() +
            ", orderNumber='" + getOrderNumber() + "'" +
            ", subtotal=" + getSubtotal() +
            ", deliveryFee=" + getDeliveryFee() +
            ", totalPrice=" + getTotalPrice() +
            ", status='" + getStatus() + "'" +
            ", orderType='" + getOrderType() + "'" +
            ", deliveryAddress='" + getDeliveryAddress() + "'" +
            ", deliveryDistrict='" + getDeliveryDistrict() + "'" +
            ", customerName='" + getCustomerName() + "'" +
            ", customerPhone='" + getCustomerPhone() + "'" +
            ", notes='" + getNotes() + "'" +
            ", createdDate='" + getCreatedDate() + "'" +
            ", lastModifiedDate='" + getLastModifiedDate() + "'" +
            ", user=" + getUser() +
            "}";
    }
}
