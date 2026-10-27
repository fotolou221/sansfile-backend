package com.sansfile.app.repository;

import com.sansfile.app.domain.AppNotification;
import com.sansfile.app.domain.enumeration.RecipientRole;
import com.sansfile.app.domain.enumeration.SmsFallbackStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository for the AppNotification entity.
 */
@Repository
public interface AppNotificationRepository extends JpaRepository<AppNotification, Long>, JpaSpecificationExecutor<AppNotification> {
    @Query(
        "select appNotification from AppNotification appNotification where appNotification.user.login = ?#{authentication.name} order by appNotification.createdDate desc"
    )
    List<AppNotification> findByUserIsCurrentUser();

    List<AppNotification> findByUserLoginOrderByCreatedDateDesc(String login);

    List<AppNotification> findByRecipientRoleOrderByCreatedDateDesc(RecipientRole role);

    // ── SMS de secours ──

    List<AppNotification> findTop50BySmsStatusAndSmsDueAtLessThanEqualOrderBySmsDueAtAsc(SmsFallbackStatus status, Instant dueAt);

    /** Change l'état du SMS de secours seulement s'il y est encore : l'envoi et l'accusé de réception se font concurrence. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AppNotification n set n.smsStatus = :to where n.id = :id and n.smsStatus = :from")
    int updateSmsStatus(@Param("id") Long id, @Param("from") SmsFallbackStatus from, @Param("to") SmsFallbackStatus to);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "update AppNotification n set n.deliveredAt = :now, n.smsStatus = com.sansfile.app.domain.enumeration.SmsFallbackStatus.AVOIDED" +
            " where n.receiptToken = :token and n.smsStatus = com.sansfile.app.domain.enumeration.SmsFallbackStatus.PENDING"
    )
    int markDeliveredByReceiptToken(@Param("token") String token, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "update AppNotification n set n.deliveredAt = :now, n.smsStatus = com.sansfile.app.domain.enumeration.SmsFallbackStatus.AVOIDED" +
            " where n.user.id = :userId and n.smsStatus = com.sansfile.app.domain.enumeration.SmsFallbackStatus.PENDING"
    )
    int markPendingDeliveredForUser(@Param("userId") Long userId, @Param("now") Instant now);

    long countBySmsStatusAndCreatedDateAfter(SmsFallbackStatus status, Instant after);
}
