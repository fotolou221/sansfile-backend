package com.sansfile.app.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Partenaire boutique d'une localité : SansFile revend ses produits (prix de vente SansFile − prix de
 * gros du partenaire = part SansFile). Son livreur livre les commandes de la localité et lui remet
 * sa part, encaissée à la livraison. Un seul partenaire par localité dans cette version.
 */
@Entity
@Table(name = "partner")
public class Partner implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator")
    @Column(name = "id")
    private Long id;

    @NotNull
    @Size(min = 2, max = 150)
    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Size(max = 100)
    @Column(name = "manager_name", length = 100)
    private String managerName;

    /** Numéro WhatsApp du partenaire (+221…) : la commande lui est envoyée sur WhatsApp. */
    @NotNull
    @Size(max = 30)
    @Column(name = "phone", length = 30, nullable = false)
    private String phone;

    @Size(max = 255)
    @Column(name = "address", length = 255)
    private String address;

    @NotNull
    @ManyToOne(optional = false)
    @JoinColumn(name = "locality_id", nullable = false, unique = true)
    private Locality locality;

    @NotNull
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    /** Livreur habituel du partenaire : pré-rempli sur les commandes, payé par SansFile (frais de livraison). */
    @Size(max = 100)
    @Column(name = "courier_name", length = 100)
    private String courierName;

    @Size(max = 30)
    @Column(name = "courier_phone", length = 30)
    private String courierPhone;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "created_date")
    private Instant createdDate;

    @Column(name = "last_modified_date")
    private Instant lastModifiedDate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getManagerName() {
        return managerName;
    }

    public void setManagerName(String managerName) {
        this.managerName = managerName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Locality getLocality() {
        return locality;
    }

    public void setLocality(Locality locality) {
        this.locality = locality;
    }

    public Boolean getActive() {
        return active;
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(active);
    }

    public void setActive(Boolean active) {
        this.active = active;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Partner)) {
            return false;
        }
        return getId() != null && getId().equals(((Partner) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Partner{id=" + id + ", name='" + name + "', active=" + active + "}";
    }
}
