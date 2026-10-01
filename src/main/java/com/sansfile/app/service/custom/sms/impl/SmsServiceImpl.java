package com.sansfile.app.service.custom.sms.impl;

import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.service.custom.sms.SmsProvider;
import com.sansfile.app.service.custom.sms.SmsService;
import com.sansfile.app.service.custom.sms.SmsUsageTracker;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implémentation du service central d'expédition des SMS.
 */
@Service
public class SmsServiceImpl implements SmsService {

    private static final Logger LOG = LoggerFactory.getLogger(SmsServiceImpl.class);

    private final ApplicationProperties applicationProperties;
    private final List<SmsProvider> smsProviders;
    private final SmsUsageTracker usageTracker;

    public SmsServiceImpl(ApplicationProperties applicationProperties, List<SmsProvider> smsProviders, SmsUsageTracker usageTracker) {
        this.applicationProperties = applicationProperties;
        this.smsProviders = smsProviders;
        this.usageTracker = usageTracker;
    }

    @Override
    public boolean sendSms(String toPhoneNumber, String message) {
        String activeProviderName = applicationProperties.getSms().getProvider();
        // Pas de repli silencieux : un fournisseur mal configuré doit se voir, pas simuler un envoi réussi
        boolean sent = smsProviders
            .stream()
            .filter(p -> p.getProviderName().equalsIgnoreCase(activeProviderName))
            .findFirst()
            .map(provider -> provider.sendSms(toPhoneNumber, message))
            .orElseGet(() -> {
                LOG.error("❌ Fournisseur SMS inconnu « {} » (valeurs possibles : mock, sendtext) : SMS non envoyé.", activeProviderName);
                return false;
            });
        usageTracker.record(sent);
        return sent;
    }

    @Override
    public boolean sendOtpCode(String toPhoneNumber, String code) {
        String message = String.format("SansFile : Votre code de connexion sécurisé est %s. Ne le partagez pas.", code);
        return sendSms(toPhoneNumber, message);
    }

    @Override
    public boolean sendTicketYourTurnAlert(String toPhoneNumber, String salonName, int ticketNumber) {
        String message = String.format(
            "SansFile : C'est bientôt votre tour chez %s (Ticket #%d) ! Merci de vous présenter au salon.",
            salonName,
            ticketNumber
        );
        return sendSms(toPhoneNumber, message);
    }
}
