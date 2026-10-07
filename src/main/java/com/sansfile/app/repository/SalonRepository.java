package com.sansfile.app.repository;

import com.sansfile.app.domain.Salon;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the Salon entity.
 */
@Repository
public interface SalonRepository extends JpaRepository<Salon, Long>, JpaSpecificationExecutor<Salon> {
    Optional<Salon> findOneBySlug(String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select salon from Salon salon where salon.id = :id")
    Optional<Salon> findByIdForUpdate(@Param("id") Long id);

    boolean existsBySlug(String slug);

    // ── Agents de terrain ──

    List<Salon> findAllByCreatedByAgentIdOrderByIdDesc(Long agentId);

    boolean existsByIdAndCreatedByAgentId(Long id, Long agentId);

    long countByCreatedByAgentId(Long agentId);

    /** Nombre de salons inscrits par agent : [agentId, total]. */
    @Query("select s.createdByAgentId, count(s) from Salon s where s.createdByAgentId is not null group by s.createdByAgentId")
    List<Object[]> countSalonsByAgent();

    @Query("select s.localityId, count(s) from Salon s where s.localityId is not null group by s.localityId")
    List<Object[]> countSalonsByLocality();

    long countByLocalityIdIsNull();

    /** Salons des localités d'un agent (y compris ceux inscrits par d'autres), par nom. */
    List<Salon> findAllByLocalityIdInOrderByNameAsc(java.util.Collection<Long> localityIds);

    /** Localités des salons inscrits par un agent : sert à l'affecter automatiquement. */
    @Query("select distinct s.localityId from Salon s where s.createdByAgentId = :agentId and s.localityId is not null")
    List<Long> findLocalityIdsOfAgentSalons(@Param("agentId") Long agentId);
}
