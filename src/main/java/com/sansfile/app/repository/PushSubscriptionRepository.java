package com.sansfile.app.repository;

import com.sansfile.app.domain.PushSubscription;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByUserId(Long userId);

    /** Abonnements push d'un compte supprimé. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from PushSubscription p where p.user.id = :userId")
    int deleteAllOfUser(@org.springframework.data.repository.query.Param("userId") Long userId);
}
