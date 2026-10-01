package com.sansfile.app.service.custom.sms;

import com.sansfile.app.config.ApplicationProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fournisseur SMS de production : API SendText (https://sendtext.sn).
 * Authentification par les en-têtes {@code SNT-API-KEY} / {@code SNT-API-SECRET}.
 * Le contenu des SMS (codes OTP) n'est jamais écrit dans les logs.
 */
@Component("sendTextSmsProvider")
public class SendTextSmsProvider implements SmsProvider {

    public static final String PROVIDER_NAME = "sendtext";

    private static final Logger LOG = LoggerFactory.getLogger(SendTextSmsProvider.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ApplicationProperties applicationProperties;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public SendTextSmsProvider(ApplicationProperties applicationProperties) {
        this.applicationProperties = applicationProperties;
    }

    @Override
    public boolean sendSms(String toPhoneNumber, String message) {
        ApplicationProperties.Sms sms = applicationProperties.getSms();
        if (!isConfigured(sms)) {
            LOG.error("❌ SendText non configuré (SENDTEXT_API_KEY / SENDTEXT_API_SECRET manquants) : SMS non envoyé.");
            return false;
        }
        String phone = toSendTextPhone(toPhoneNumber);
        if (phone == null) {
            LOG.error("❌ Numéro refusé pour SendText : {}", mask(toPhoneNumber));
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender_name", sms.getSenderName());
        body.put("sms_type", sms.getSendtextSmsType());
        body.put("phone", phone);
        body.put("text", message);

        try {
            HttpRequest request = authorizedRequest(sms, "/sms")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                LOG.error(
                    "❌ SendText a refusé l'envoi vers {} : HTTP {} ({})",
                    mask(phone),
                    response.statusCode(),
                    errorMessage(response.body())
                );
                return false;
            }
            Map<?, ?> result = jsonMapper.readValue(response.body(), Map.class);
            LOG.info(
                "📨 SMS SendText accepté pour {} (messageId={}, statut={})",
                mask(phone),
                result.get("messageId"),
                result.get("statusDescription")
            );
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("❌ Envoi SendText interrompu vers {}", mask(phone));
            return false;
        } catch (Exception e) {
            LOG.error("❌ Échec de l'appel SendText vers {} : {}", mask(phone), e.getMessage());
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /** Vérifie les identifiants au démarrage via le solde (aucun SMS envoyé). */
    @EventListener(ApplicationReadyEvent.class)
    public void checkAccountOnStartup() {
        if (!PROVIDER_NAME.equalsIgnoreCase(applicationProperties.getSms().getProvider())) {
            return;
        }
        Balance balance = fetchBalance();
        if (balance.error() != null) {
            LOG.error("❌ SendText : {}", balance.error());
        } else {
            LOG.info("✅ SendText connecté — solde : {} SMS (expire le {})", balance.remainingSms(), balance.expiresAt());
        }
    }

    /**
     * Solde du compte SendText (route /balance, aucun SMS envoyé).
     * En cas d'échec, {@code error} explique la cause sans exposer les clés.
     */
    public Balance fetchBalance() {
        ApplicationProperties.Sms sms = applicationProperties.getSms();
        if (!isConfigured(sms)) {
            return Balance.failure("clés API absentes (SENDTEXT_API_KEY / SENDTEXT_API_SECRET) : aucun SMS ne peut partir.");
        }
        try {
            HttpRequest request = authorizedRequest(sms, "/balance").GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return Balance.failure(
                    "identifiants refusés (HTTP " + response.statusCode() + " : " + errorMessage(response.body()) + ")."
                );
            }
            Map<?, ?> json = jsonMapper.readValue(response.body(), Map.class);
            Object expiresAt = json.get("expires_at");
            return new Balance(toLong(json.get("balance")), expiresAt != null ? expiresAt.toString() : null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Balance.failure("vérification interrompue.");
        } catch (Exception e) {
            return Balance.failure("service injoignable (" + e.getClass().getSimpleName() + ").");
        }
    }

    /** Solde SendText : nombre de SMS restants et date d'expiration du forfait (format renvoyé par SendText). */
    public record Balance(Long remainingSms, String expiresAt, String error) {
        static Balance failure(String error) {
            return new Balance(null, null, error);
        }
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value != null ? Math.round(Double.parseDouble(value.toString().trim())) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private HttpRequest.Builder authorizedRequest(ApplicationProperties.Sms sms, String path) {
        String baseUrl = sms.getSendtextBaseUrl().replaceAll("/+$", "");
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("SNT-API-KEY", sms.getSendtextApiKey().trim())
            .header("SNT-API-SECRET", sms.getSendtextApiSecret().trim());
    }

    private static boolean isConfigured(ApplicationProperties.Sms sms) {
        return !isBlank(sms.getSendtextApiKey()) && !isBlank(sms.getSendtextApiSecret()) && !isBlank(sms.getSendtextBaseUrl());
    }

    /** SendText attend le format international sans « + » : 221XXXXXXXXX. */
    static String toSendTextPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.length() == 9) {
            digits = "221" + digits;
        }
        return digits.matches("^221\\d{9}$") ? digits : null;
    }

    /** Message d'erreur renvoyé par SendText, sans jamais recopier le contenu du SMS. */
    private String errorMessage(String body) {
        try {
            Map<?, ?> json = jsonMapper.readValue(body, Map.class);
            Object message = json.get("message") != null ? json.get("message") : json.get("Message");
            return message != null ? message.toString() : "réponse sans message";
        } catch (Exception e) {
            return "réponse illisible";
        }
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 4) {
            return "***";
        }
        return "***" + phone.substring(phone.length() - 4);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
