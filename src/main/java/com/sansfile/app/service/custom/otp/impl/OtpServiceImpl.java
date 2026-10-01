package com.sansfile.app.service.custom.otp.impl;

import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.domain.OtpVerification;
import com.sansfile.app.domain.enumeration.OtpStatus;
import com.sansfile.app.repository.OtpVerificationRepository;
import com.sansfile.app.service.custom.otp.OtpService;
import com.sansfile.app.service.custom.sms.SmsService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implémentation du service gérant le cycle de vie des codes OTP.
 */
@Service
@Transactional
public class OtpServiceImpl implements OtpService {

    private static final Logger LOG = LoggerFactory.getLogger(OtpServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Mobiles sénégalais uniquement : bloque la fraude aux SMS vers des numéros internationaux surtaxés. */
    private static final java.util.regex.Pattern SENEGAL_MOBILE = java.util.regex.Pattern.compile("^\\+2217[05678]\\d{7}$");
    private static final int MAX_CODES_PER_PHONE_PER_HOUR = 5;

    private final OtpVerificationRepository otpVerificationRepository;
    private final SmsService smsService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationProperties applicationProperties;

    public OtpServiceImpl(
        OtpVerificationRepository otpVerificationRepository,
        SmsService smsService,
        PasswordEncoder passwordEncoder,
        ApplicationProperties applicationProperties
    ) {
        this.otpVerificationRepository = otpVerificationRepository;
        this.smsService = smsService;
        this.passwordEncoder = passwordEncoder;
        this.applicationProperties = applicationProperties;
    }

    @Override
    public String normalizePhoneNumber(String rawPhone) {
        if (rawPhone == null) {
            return "";
        }
        String digits = rawPhone.replaceAll("[^0-9+]", "").trim();
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        } else if (!digits.startsWith("+")) {
            if (digits.startsWith("221")) {
                digits = "+" + digits;
            } else if (digits.length() == 9) {
                digits = "+221" + digits;
            }
        }
        return digits;
    }

    @Override
    public boolean generateAndSendOtp(String rawPhone) {
        String phone = normalizePhoneNumber(rawPhone);
        if (!SENEGAL_MOBILE.matcher(phone).matches()) {
            throw new IllegalArgumentException("Numéro invalide : saisissez un numéro mobile sénégalais (70, 75, 76, 77 ou 78).");
        }

        // Délai minimal entre deux envois, imposé côté serveur (anti-harcèlement par SMS)
        Instant now = Instant.now();
        int cooldown = applicationProperties.getOtp().getResendCooldownSeconds();
        Optional<OtpVerification> last = otpVerificationRepository.findTopByPhoneOrderByCreatedDateDesc(phone);
        if (last.isPresent() && last.get().getCreatedDate() != null && last.get().getCreatedDate().isAfter(now.minusSeconds(cooldown))) {
            long wait = cooldown - Duration.between(last.get().getCreatedDate(), now).getSeconds();
            throw new IllegalStateException("Veuillez patienter " + Math.max(wait, 1) + " s avant de demander un nouveau code.");
        }

        long countRecent = otpVerificationRepository.countByPhoneAndCreatedDateAfter(phone, now.minus(1, ChronoUnit.HOURS));
        if (countRecent >= MAX_CODES_PER_PHONE_PER_HOUR) {
            LOG.warn("⚠️ Limite horaire d'OTP atteinte pour {}", phone);
            throw new IllegalStateException("Trop de codes demandés. Réessayez dans une heure.");
        }

        // Un seul code valide à la fois : les précédents sont invalidés
        otpVerificationRepository.findByPhoneAndStatus(phone, OtpStatus.PENDING).forEach(previous -> previous.setStatus(OtpStatus.EXPIRED));

        int codeInt = 100000 + RANDOM.nextInt(900000);
        String code = String.valueOf(codeInt);
        String codeHash = passwordEncoder.encode(code);

        int expirationSeconds = applicationProperties.getOtp().getExpirationSeconds();
        Instant expiresAt = Instant.now().plus(expirationSeconds, ChronoUnit.SECONDS);

        OtpVerification otp = new OtpVerification();
        otp.setPhone(phone);
        otp.setCodeHash(codeHash);
        otp.setStatus(OtpStatus.PENDING);
        otp.setAttemptsCount(0);
        otp.setExpiresAt(expiresAt);
        otp.setCreatedDate(Instant.now());

        otpVerificationRepository.save(otp);

        if (!smsService.sendOtpCode(phone, code)) {
            // Rollback : le code non reçu ne compte ni dans le délai ni dans la limite horaire
            throw new IllegalStateException("L'envoi du SMS a échoué. Réessayez dans quelques instants.");
        }
        return true;
    }

    /** Code de test accepté uniquement avec le fournisseur « mock » (développement), jamais avec SendText. */
    private boolean isTestCode(String inputCode) {
        String testCode = applicationProperties.getOtp().getTestCode();
        return (
            "mock".equalsIgnoreCase(applicationProperties.getSms().getProvider()) &&
            testCode != null &&
            !testCode.isBlank() &&
            testCode.equals(inputCode)
        );
    }

    /**
     * Transaction propre : le compteur d'essais et le statut du code sont enregistrés même quand l'appelant
     * lève une exception (code refusé), sinon la limite de tentatives ne serait jamais atteinte.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VerificationResult verifyOtp(String rawPhone, String inputCode) {
        String phone = normalizePhoneNumber(rawPhone);
        Optional<OtpVerification> optOtp = otpVerificationRepository.findTopByPhoneAndStatusOrderByCreatedDateDesc(
            phone,
            OtpStatus.PENDING
        );

        if (optOtp.isEmpty()) {
            LOG.warn("Aucun code OTP en attente pour {}", phone);
            return VerificationResult.of(VerificationStatus.NO_PENDING_CODE);
        }

        OtpVerification otp = optOtp.get();

        if (Instant.now().isAfter(otp.getExpiresAt())) {
            otp.setStatus(OtpStatus.EXPIRED);
            otpVerificationRepository.save(otp);
            LOG.warn("Code OTP expiré pour {}", phone);
            return VerificationResult.of(VerificationStatus.EXPIRED);
        }

        int maxAttempts = applicationProperties.getOtp().getMaxAttempts();
        if (otp.getAttemptsCount() >= maxAttempts) {
            otp.setStatus(OtpStatus.MAX_ATTEMPTS_EXCEEDED);
            otpVerificationRepository.save(otp);
            LOG.warn("Nombre maximal de tentatives OTP dépassé pour {}", phone);
            return VerificationResult.of(VerificationStatus.TOO_MANY_ATTEMPTS);
        }

        otp.setAttemptsCount(otp.getAttemptsCount() + 1);

        boolean matches = passwordEncoder.matches(inputCode, otp.getCodeHash()) || isTestCode(inputCode);

        if (matches) {
            otp.setStatus(OtpStatus.VERIFIED);
            otpVerificationRepository.save(otp);
            LOG.info("✅ Code OTP validé avec succès pour {}", phone);
            return VerificationResult.of(VerificationStatus.VALID);
        }

        int remaining = Math.max(0, maxAttempts - otp.getAttemptsCount());
        if (remaining == 0) {
            // Dernière tentative épuisée : le code est invalidé tout de suite
            otp.setStatus(OtpStatus.MAX_ATTEMPTS_EXCEEDED);
        }
        otpVerificationRepository.save(otp);
        LOG.warn("❌ Code OTP incorrect pour {} (Tentative {}/{})", phone, otp.getAttemptsCount(), maxAttempts);
        return remaining == 0
            ? VerificationResult.of(VerificationStatus.TOO_MANY_ATTEMPTS)
            : new VerificationResult(VerificationStatus.WRONG_CODE, remaining);
    }
}
