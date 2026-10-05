package com.sansfile.app.domain;

import com.sansfile.app.domain.enumeration.AgentAction;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Ligne du journal des agents de terrain. Jamais modifiée après écriture.
 */
@Entity
@Table(name = "agent_activity")
public class AgentActivity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator")
    @Column(name = "id")
    private Long id;

    /** Compte agent concerné. */
    @NotNull
    @Column(name = "agent_id", nullable = false, updatable = false)
    private Long agentId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 40, nullable = false, updatable = false)
    private AgentAction action;

    @Size(max = 500)
    @Column(name = "description", length = 500, updatable = false)
    private String description;

    @Column(name = "salon_id")
    private Long salonId;

    /** Qui a agi : l'agent lui-même ou l'administrateur (identifiant de connexion). */
    @Size(max = 254)
    @Column(name = "actor", length = 254, updatable = false)
    private String actor;

    @Size(max = 64)
    @Column(name = "ip_address", length = 64, updatable = false)
    private String ipAddress;

    @Size(max = 255)
    @Column(name = "user_agent", length = 255, updatable = false)
    private String userAgent;

    @NotNull
    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

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

    public AgentAction getAction() {
        return action;
    }

    public void setAction(AgentAction action) {
        this.action = action;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getSalonId() {
        return salonId;
    }

    public void setSalonId(Long salonId) {
        this.salonId = salonId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
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
        if (!(o instanceof AgentActivity)) {
            return false;
        }
        return getId() != null && getId().equals(((AgentActivity) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "AgentActivity{id=" + id + ", agentId=" + agentId + ", action=" + action + ", createdDate=" + createdDate + "}";
    }
}
