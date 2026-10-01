package com.sansfile.app.service.custom.otp;

/**
 * Interface du service gérant le cycle de vie des codes OTP.
 */
public interface OtpService {
    /**
     * Nettoie et normalise le numéro de téléphone au format sénégalais/international.
     */
    String normalizePhoneNumber(String rawPhone);

    /**
     * Génère et expédie un code OTP par SMS.
     */
    boolean generateAndSendOtp(String rawPhone);

    /**
     * Valide un code OTP saisi par l'utilisateur et précise la cause d'un échec.
     */
    VerificationResult verifyOtp(String rawPhone, String inputCode);

    enum VerificationStatus {
        VALID,
        NO_PENDING_CODE,
        EXPIRED,
        TOO_MANY_ATTEMPTS,
        WRONG_CODE,
    }

    /** Résultat d'une vérification ; {@code remainingAttempts} n'a de sens que pour {@link VerificationStatus#WRONG_CODE}. */
    record VerificationResult(VerificationStatus status, int remainingAttempts) {
        public static VerificationResult of(VerificationStatus status) {
            return new VerificationResult(status, 0);
        }

        public boolean isValid() {
            return status == VerificationStatus.VALID;
        }
    }
}
