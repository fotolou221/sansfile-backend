package com.sansfile.app.repository;

import com.sansfile.app.domain.Partner;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PartnerRepository extends JpaRepository<Partner, Long> {
    List<Partner> findAllByOrderByNameAsc();

    Optional<Partner> findOneByLocalityId(Long localityId);
}
