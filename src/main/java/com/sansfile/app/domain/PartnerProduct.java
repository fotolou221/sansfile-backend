package com.sansfile.app.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Produit du catalogue proposé par un partenaire : prix de gros (part du partenaire par unité),
 * disponibilité et quantité en stock. Réservé à l'administration : jamais exposé par l'API publique des produits.
 */
@Entity
@Table(name = "partner_product")
public class PartnerProduct implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator")
    @Column(name = "id")
    private Long id;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id", nullable = false)
    private Partner partner;

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @NotNull
    @Min(0)
    @Column(name = "wholesale_price", nullable = false)
    private Long wholesalePrice;

    @NotNull
    @Column(name = "available", nullable = false)
    private Boolean available = true;

    /** Quantité en stock chez le partenaire : à zéro, le produit n'est plus proposé dans sa localité. */
    @NotNull
    @Min(0)
    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity = 0;

    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Partner getPartner() {
        return partner;
    }

    public void setPartner(Partner partner) {
        this.partner = partner;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public Long getWholesalePrice() {
        return wholesalePrice;
    }

    public void setWholesalePrice(Long wholesalePrice) {
        this.wholesalePrice = wholesalePrice;
    }

    public Boolean getAvailable() {
        return available;
    }

    public boolean isAvailable() {
        return Boolean.TRUE.equals(available);
    }

    public void setAvailable(Boolean available) {
        this.available = available;
    }

    public int getStockQuantity() {
        return stockQuantity == null ? 0 : stockQuantity;
    }

    public void setStockQuantity(Integer stockQuantity) {
        this.stockQuantity = stockQuantity == null ? 0 : Math.max(0, stockQuantity);
    }

    /** Proposé par le partenaire et encore en stock. */
    public boolean isOrderable() {
        return isAvailable() && getStockQuantity() > 0;
    }

    public Instant getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(Instant lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PartnerProduct)) {
            return false;
        }
        return getId() != null && getId().equals(((PartnerProduct) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return (
            "PartnerProduct{id=" +
            id +
            ", wholesalePrice=" +
            wholesalePrice +
            ", available=" +
            available +
            ", stockQuantity=" +
            stockQuantity +
            "}"
        );
    }
}
