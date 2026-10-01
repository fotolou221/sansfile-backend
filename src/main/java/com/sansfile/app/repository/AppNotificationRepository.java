package com.sansfile.app.repository;

import com.sansfile.app.domain.AppNotification;
import com.sansfile.app.domain.enumeration.RecipientRole;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the AppNotification entity.
 */
@SuppressWarnings("unused")
@Repository
public interface AppNotificationRepository extends JpaRepository<AppNotification, Long>, JpaSpecificationExecutor<AppNotification> {
    @Query(
        "select appNotification from AppNotification appNotification where appNotification.user.login = ?#{authentication.name} order by appNotification.createdDate desc"
    )
    List<AppNotification> findByUserIsCurrentUser();

    List<AppNotification> findByUserLoginOrderByCreatedDateDesc(String login);

    List<AppNotification> findByRecipientRoleOrderByCreatedDateDesc(RecipientRole role);
}
