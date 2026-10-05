package com.sansfile.app.repository;

import com.sansfile.app.domain.AgentActivity;
import com.sansfile.app.domain.enumeration.AgentAction;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Journal des agents de terrain.
 */
@Repository
public interface AgentActivityRepository extends JpaRepository<AgentActivity, Long> {
    /** Filtres facultatifs (null = tous), du plus récent au plus ancien. */
    @Query(
        value = "select a from AgentActivity a where (:agentId is null or a.agentId = :agentId)" +
            " and (:action is null or a.action = :action) order by a.createdDate desc, a.id desc",
        countQuery = "select count(a) from AgentActivity a where (:agentId is null or a.agentId = :agentId)" +
            " and (:action is null or a.action = :action)"
    )
    Page<AgentActivity> search(@Param("agentId") Long agentId, @Param("action") AgentAction action, Pageable pageable);

    /** Dernière occurrence d'une action par agent : [agentId, date]. */
    @Query("select a.agentId, max(a.createdDate) from AgentActivity a where a.action = :action group by a.agentId")
    List<Object[]> findLastDateByAgent(@Param("action") AgentAction action);

    long countByAgentIdAndActionAndCreatedDateGreaterThanEqual(Long agentId, AgentAction action, Instant from);
}
