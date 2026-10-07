package com.sansfile.app.repository;

import com.sansfile.app.domain.AgentLocality;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentLocalityRepository extends JpaRepository<AgentLocality, Long> {
    List<AgentLocality> findAllByAgentId(Long agentId);

    boolean existsByAgentIdAndLocalityId(Long agentId, Long localityId);

    boolean existsByAgentId(Long agentId);

    @Query("select al.locality.id, count(al) from AgentLocality al group by al.locality.id")
    List<Object[]> countAgentsByLocality();
}
