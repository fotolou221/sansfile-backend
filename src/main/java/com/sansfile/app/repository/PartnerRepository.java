package com.sansfile.app.repository;

import com.sansfile.app.domain.Partner;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface PartnerRepository extends JpaRepository<Partner, Long> {
    List<Partner> findAllByOrderByNameAsc();

    Optional<Partner> findOneByLocalityId(Long localityId);

    /** Boutiques ouvertes et situées : partenaire actif, localité active, position renseignée. */
    @Query(
        "select p from Partner p join fetch p.locality l" +
            " where p.active = true and l.active = true and p.latitude is not null and p.longitude is not null"
    )
    List<Partner> findOpenShops();
}
