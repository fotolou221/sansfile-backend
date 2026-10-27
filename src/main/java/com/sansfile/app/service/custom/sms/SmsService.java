package com.sansfile.app.service.custom.sms;

/**
 * Interface du service central d'expédition des SMS.
 */
public interface SmsService {
    /**
     * Expédie un SMS en utilisant le provider configuré.
     */
    boolean sendSms(String toPhoneNumber, String message);

    /**
     * Envoi de SMS de code OTP de connexion.
     */
    boolean sendOtpCode(String toPhoneNumber, String code);

    /**
     * Envoi de SMS d'alerte de tour imminent au salon.
     */
    boolean sendTicketYourTurnAlert(String toPhoneNumber, String salonName, int ticketNumber);

    /** Texte du SMS d'alerte de tour imminent. */
    static String ticketYourTurnMessage(String salonName, int ticketNumber) {
        return String.format(
            "SansFile : C'est bientôt votre tour chez %s (Ticket #%d) ! Merci de vous présenter au salon.",
            salonName,
            ticketNumber
        );
    }
}
