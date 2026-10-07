package com.sansfile.app.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sansfile.app.domain.enumeration.OrderStatus;
import com.sansfile.app.domain.enumeration.OrderType;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Commande passée sur la boutique via WhatsApp ou Appel direct
 */
@Entity
@Table(name = "boutique_order")
@SuppressWarnings("common-java:DuplicatedBlocks")
public class BoutiqueOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator")
    @Column(name = "id")
    private Long id;

    @NotNull
    @Size(max = 50)
    @Column(name = "order_number", length = 50, nullable = false, unique = true)
    private String orderNumber;

    @NotNull
    @Min(value = 0L)
    @Column(name = "subtotal", nullable = false)
    private Long subtotal;

    @NotNull
    @Min(value = 0L)
    @Column(name = "delivery_fee", nullable = false)
    private Long deliveryFee;

    @NotNull
    @Min(value = 0L)
    @Column(name = "total_price", nullable = false)
    private Long totalPrice;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false)
    private OrderType orderType;

    @Size(max = 255)
    @Column(name = "delivery_address", length = 255)
    private String deliveryAddress;

    @Size(max = 100)
    @Column(name = "delivery_district", length = 100)
    private String deliveryDistrict;

    @Size(max = 100)
    @Column(name = "customer_name", length = 100)
    private String customerName;

    @Size(max = 30)
    @Column(name = "customer_phone", length = 30)
    private String customerPhone;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @NotNull
    @Column(name = "created_date", nullable = false)
    private Instant createdDate;

    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "order")
    @JsonIgnoreProperties(value = { "product", "order" }, allowSetters = true)
    private Set<OrderItem> itemses = new HashSet<>();

    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    /** Localité de livraison ; son nom est recopié dans {@code deliveryDistrict} au moment de la commande. */
    @Column(name = "locality_id")
    private Long localityId;

    /** Partenaire de la localité qui fournit et livre la commande (nom et téléphone figés à la commande). */
    @Column(name = "partner_id")
    private Long partnerId;

    @Size(max = 150)
    @Column(name = "partner_name", length = 150)
    private String partnerName;

    @Size(max = 30)
    @Column(name = "partner_phone", length = 30)
    private String partnerPhone;

    /** Acompte envoyé par le client avant confirmation : part SansFile + frais de livraison. */
    @Min(value = 0L)
    @Column(name = "upfront_amount")
    private Long upfrontAmount;

    /** Part du partenaire (prix de gros × quantités), encaissée par son livreur à la livraison. */
    @Min(value = 0L)
    @Column(name = "partner_amount")
    private Long partnerAmount;

    @Size(max = 100)
    @Column(name = "courier_name", length = 100)
    private String courierName;

    @Size(max = 30)
    @Column(name = "courier_phone", length = 30)
    private String courierPhone;

    /** Le livreur du partenaire a été payé par SansFile (frais de livraison). */
    @NotNull
    @Column(name = "courier_paid", nullable = false)
    private Boolean courierPaid = false;

    @Column(name = "delivery_latitude")
    private Double deliveryLatitude;

    @Column(name = "delivery_longitude")
    private Double deliveryLongitude;

    /** Quantités retirées du stock du partenaire (acompte reçu) : remises en stock si la commande est annulée. */
    @NotNull
    @Column(name = "stock_deducted", nullable = false)
    private Boolean stockDeducted = false;

    /** Lien de la facture envoyée au partenaire (impossible à deviner, sans connexion). */
    @Column(name = "invoice_token", length = 64, unique = true)
    private String invoiceToken;

    // jhipster-needle-entity-add-field - JHipster will add fields here

    public Long getId() {
        return this.id;
    }

    public BoutiqueOrder id(Long id) {
        this.setId(id);
        return this;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNumber() {
        return this.orderNumber;
    }

    public BoutiqueOrder orderNumber(String orderNumber) {
        this.setOrderNumber(orderNumber);
        return this;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public Long getSubtotal() {
        return this.subtotal;
    }

    public BoutiqueOrder subtotal(Long subtotal) {
        this.setSubtotal(subtotal);
        return this;
    }

    public void setSubtotal(Long subtotal) {
        this.subtotal = subtotal;
    }

    public Long getDeliveryFee() {
        return this.deliveryFee;
    }

    public BoutiqueOrder deliveryFee(Long deliveryFee) {
        this.setDeliveryFee(deliveryFee);
        return this;
    }

    public void setDeliveryFee(Long deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public Long getTotalPrice() {
        return this.totalPrice;
    }

    public BoutiqueOrder totalPrice(Long totalPrice) {
        this.setTotalPrice(totalPrice);
        return this;
    }

    public void setTotalPrice(Long totalPrice) {
        this.totalPrice = totalPrice;
    }

    public OrderStatus getStatus() {
        return this.status;
    }

    public BoutiqueOrder status(OrderStatus status) {
        this.setStatus(status);
        return this;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public OrderType getOrderType() {
        return this.orderType;
    }

    public BoutiqueOrder orderType(OrderType orderType) {
        this.setOrderType(orderType);
        return this;
    }

    public void setOrderType(OrderType orderType) {
        this.orderType = orderType;
    }

    public String getDeliveryAddress() {
        return this.deliveryAddress;
    }

    public BoutiqueOrder deliveryAddress(String deliveryAddress) {
        this.setDeliveryAddress(deliveryAddress);
        return this;
    }

    public void setDeliveryAddress(String deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    public String getDeliveryDistrict() {
        return this.deliveryDistrict;
    }

    public BoutiqueOrder deliveryDistrict(String deliveryDistrict) {
        this.setDeliveryDistrict(deliveryDistrict);
        return this;
    }

    public void setDeliveryDistrict(String deliveryDistrict) {
        this.deliveryDistrict = deliveryDistrict;
    }

    public String getCustomerName() {
        return this.customerName;
    }

    public BoutiqueOrder customerName(String customerName) {
        this.setCustomerName(customerName);
        return this;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return this.customerPhone;
    }

    public BoutiqueOrder customerPhone(String customerPhone) {
        this.setCustomerPhone(customerPhone);
        return this;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String getNotes() {
        return this.notes;
    }

    public BoutiqueOrder notes(String notes) {
        this.setNotes(notes);
        return this;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedDate() {
        return this.createdDate;
    }

    public BoutiqueOrder createdDate(Instant createdDate) {
        this.setCreatedDate(createdDate);
        return this;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    public Instant getLastModifiedDate() {
        return this.lastModifiedDate;
    }

    public BoutiqueOrder lastModifiedDate(Instant lastModifiedDate) {
        this.setLastModifiedDate(lastModifiedDate);
        return this;
    }

    public void setLastModifiedDate(Instant lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public Set<OrderItem> getItemses() {
        return this.itemses;
    }

    public void setItemses(Set<OrderItem> orderItems) {
        if (this.itemses != null) {
            this.itemses.forEach(i -> i.setOrder(null));
        }
        if (orderItems != null) {
            orderItems.forEach(i -> i.setOrder(this));
        }
        this.itemses = orderItems;
    }

    public BoutiqueOrder itemses(Set<OrderItem> orderItems) {
        this.setItemses(orderItems);
        return this;
    }

    public BoutiqueOrder addItems(OrderItem orderItem) {
        this.itemses.add(orderItem);
        orderItem.setOrder(this);
        return this;
    }

    public BoutiqueOrder removeItems(OrderItem orderItem) {
        this.itemses.remove(orderItem);
        orderItem.setOrder(null);
        return this;
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
        // Colonne non nulle : une commande créée sans cette information n'est pas encore payée au livreur
        this.courierPaid = Boolean.TRUE.equals(courierPaid);
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

    public Boolean getStockDeducted() {
        return stockDeducted;
    }

    public void setStockDeducted(Boolean stockDeducted) {
        this.stockDeducted = Boolean.TRUE.equals(stockDeducted);
    }

    public String getInvoiceToken() {
        return invoiceToken;
    }

    public void setInvoiceToken(String invoiceToken) {
        this.invoiceToken = invoiceToken;
    }

    public User getUser() {
        return this.user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public BoutiqueOrder user(User user) {
        this.setUser(user);
        return this;
    }

    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BoutiqueOrder)) {
            return false;
        }
        return getId() != null && getId().equals(((BoutiqueOrder) o).getId());
    }

    @Override
    public int hashCode() {
        // see https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
        return getClass().hashCode();
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "BoutiqueOrder{" +
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
            "}";
    }
}
