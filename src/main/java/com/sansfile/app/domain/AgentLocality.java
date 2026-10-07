package com.sansfile.app.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Localité où un agent de terrain peut exercer. Table à part (et non relation sur {@link User}) :
 * les comptes sont mis en cache Redis et ne doivent pas porter d'associations paresseuses.
 */
@Entity
@Table(name = "agent_locality")
public class AgentLocality implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator")
    @Column(name = "id")
    private Long id;

    @NotNull
    @Column(name = "agent_id", nullable = false, updatable = false)
    private Long agentId;

    @NotNull
    @ManyToOne(optional = false)
    @JoinColumn(name = "locality_id", nullable = false, updatable = false)
    private Locality locality;

    @Column(name = "created_date", updatable = false)
    private Instant createdDate;

    public AgentLocality() {}

    public AgentLocality(Long agentId, Locality locality) {
        this.agentId = agentId;
        this.locality = locality;
        this.createdDate = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public Locality getLocality() {
        return locality;
    }

    public void setLocality(Locality locality) {
        this.locality = locality;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AgentLocality)) {
            return false;
        }
        return getId() != null && getId().equals(((AgentLocality) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
