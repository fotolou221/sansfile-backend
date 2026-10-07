package com.sansfile.app.repository;

import com.sansfile.app.domain.Locality;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LocalityRepository extends JpaRepository<Locality, Long> {
    List<Locality> findAllByOrderByNameAsc();

    List<Locality> findAllByActiveTrueOrderByNameAsc();

    Optional<Locality> findOneByNameIgnoreCase(String name);
}
