package com.sansfile.app.repository;

import com.sansfile.app.domain.OtpVerification;
import com.sansfile.app.domain.enumeration.OtpStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the OtpVerification entity.
 */
@SuppressWarnings("unused")
@Repository
public interface OtpVerificationRepository extends JpaRepository<OtpVerification, Long> {
    Optional<OtpVerification> findTopByPhoneAndStatusOrderByCreatedDateDesc(String phone, OtpStatus status);

    long countByPhoneAndCreatedDateAfter(String phone, Instant after);

    /** Codes OTP générés depuis une date (un code = un SMS facturé). */
    long countByCreatedDateAfter(Instant after);

    Optional<OtpVerification> findTopByPhoneOrderByCreatedDateDesc(String phone);

    java.util.List<OtpVerification> findByPhoneAndStatus(String phone, OtpStatus status);
}
