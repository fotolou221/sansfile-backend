package com.sansfile.app.repository;

import com.sansfile.app.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link User} entity.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    String USERS_BY_LOGIN_CACHE = "usersByLogin";

    String USERS_BY_EMAIL_CACHE = "usersByEmail";
    Optional<User> findOneByActivationKey(String activationKey);
    List<User> findAllByActivatedIsFalseAndActivationKeyIsNotNullAndCreatedDateBefore(Instant dateTime);
    Optional<User> findOneByResetKey(String resetKey);
    Optional<User> findOneByEmailIgnoreCase(String email);
    Optional<User> findOneByLogin(String login);
    Optional<User> findOneByPhone(String phone);

    @EntityGraph(attributePaths = "authorities")
    @Cacheable(cacheNames = USERS_BY_LOGIN_CACHE, unless = "#result == null")
    Optional<User> findOneWithAuthoritiesByLogin(String login);

    @EntityGraph(attributePaths = "authorities")
    @Cacheable(cacheNames = USERS_BY_EMAIL_CACHE, unless = "#result == null")
    Optional<User> findOneWithAuthoritiesByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = "authorities")
    Optional<User> findOneWithAuthoritiesByPhone(String phone);

    Page<User> findAllByIdNotNullAndActivatedIsTrue(Pageable pageable);

    /** Comptes ayant ce rôle (ex. agents de terrain), du plus récent au plus ancien. */
    @Query("select u from User u where exists (select a from u.authorities a where a.name = :authority) order by u.id desc")
    List<User> findAllByAuthorityName(@Param("authority") String authority);

    @Query("select u.localityId, count(u) from User u where u.localityId is not null group by u.localityId")
    List<Object[]> countUsersByLocality();

    /** Zones demandées par les utilisateurs dont la localité n'existe pas encore. */
    @Query("select u.requestedLocality from User u where u.localityId is null and u.requestedLocality is not null")
    List<String> findRequestedLocalities();

    @Query("select u from User u where u.localityId is null and lower(trim(u.requestedLocality)) = lower(trim(:name))")
    List<User> findAllWaitingForLocality(@Param("name") String name);
}
