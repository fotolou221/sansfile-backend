package com.sansfile.app.service.custom.notification;

import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.domain.AppNotification;
import com.sansfile.app.domain.User;
import com.sansfile.app.domain.enumeration.NotificationType;
import com.sansfile.app.domain.enumeration.RecipientRole;
import com.sansfile.app.domain.enumeration.SmsFallbackStatus;
import com.sansfile.app.repository.AppNotificationRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.service.custom.otp.PhoneNumbers;
import com.sansfile.app.service.custom.push.BrowserPushService;
import com.sansfile.app.service.custom.realtime.RealtimeEventService;
import com.sansfile.app.service.custom.sms.SmsService;
import com.sansfile.app.service.mapper.AppNotificationMapper;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Notifications in-app, avec SMS de secours. Hors code de connexion, un message part d'abord dans l'app (bandeau
 * à l'écran, notification du téléphone) ; le SMS, payant, ne part que si ni l'app ouverte ni le téléphone n'en ont
 * accusé réception dans le délai (téléphone hors connexion, notifications refusées, app fermée sans push…).
 * <p>
 * Pas de transaction propre : les notifications rejoignent celle de l'appelant (file d'attente), et l'envoi des SMS
 * de secours n'enveloppe pas ses mises à jour, chacune courte et indépendante (pas de connexion gardée pendant l'envoi).
 */
@Service
public class NotificationDeliveryService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern RECEIPT_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");
    /** Au-delà (serveur arrêté entre-temps), une alerte de file d'attente n'a plus de sens : le SMS ne part pas. */
    private static final Duration MAX_SMS_LATENESS = Duration.ofMinutes(10);

    private final AppNotificationRepository appNotificationRepository;
    private final AppNotificationMapper appNotificationMapper;
    private final UserRepository userRepository;
    private final RealtimeEventService realtimeEventService;
    private final BrowserPushService browserPushService;
    private final SmsService smsService;
    private final ApplicationProperties applicationProperties;

    public NotificationDeliveryService(
        AppNotificationRepository appNotificationRepository,
        AppNotificationMapper appNotificationMapper,
        UserRepository userRepository,
        RealtimeEventService realtimeEventService,
        BrowserPushService browserPushService,
        SmsService smsService,
        ApplicationProperties applicationProperties
    ) {
        this.appNotificationRepository = appNotificationRepository;
        this.appNotificationMapper = appNotificationMapper;
        this.userRepository = userRepository;
        this.realtimeEventService = realtimeEventService;
        this.browserPushService = browserPushService;
        this.smsService = smsService;
        this.applicationProperties = applicationProperties;
    }

    /** Notification dans l'app, sans SMS. */
    public void notifyInApp(User user, RecipientRole role, NotificationType type, String title, String message, String targetRoute) {
        try {
            save(user, role, type, title, message, targetRoute, null, null);
        } catch (Exception e) {
            LOG.warn("⚠️ Erreur création notification in-app : {}", e.getMessage());
        }
    }

    /**
     * Notification dans l'app ; le SMS part au numéro indiqué si elle n'est pas reçue dans le délai
     * ({@code application.sms.fallback-delay-seconds}).
     */
    public void notifyWithSmsFallback(
        User user,
        RecipientRole role,
        NotificationType type,
        String title,
        String message,
        String targetRoute,
        String smsPhone,
        String smsMessage
    ) {
        try {
            save(user, role, type, title, message, targetRoute, smsPhone, smsMessage);
        } catch (Exception e) {
            // Sans notification enregistrée, aucun accusé de réception possible : le SMS part tout de suite
            LOG.warn("⚠️ Notification in-app impossible ({}) : SMS envoyé directement", e.getMessage());
            smsService.sendSms(smsPhone, smsMessage);
        }
    }

    /**
     * Message pour un numéro de téléphone : notification avec SMS de secours si le numéro a un compte SansFile,
     * sinon SMS tout de suite (client sans compte, proche, client direct du salon).
     */
    public void notifyPhone(String rawPhone, NotificationType type, String title, String message, String targetRoute, String smsMessage) {
        String phone = PhoneNumbers.normalize(rawPhone);
        if (phone.isBlank()) {
            return;
        }
        Optional<User> account = userRepository
            .findOneByLogin(phone)
            .or(() -> userRepository.findOneByPhone(phone))
            .filter(User::isActivated);
        if (account.isPresent()) {
            notifyWithSmsFallback(account.get(), RecipientRole.CLIENT, type, title, message, targetRoute, phone, smsMessage);
        } else {
            smsService.sendSms(phone, smsMessage);
        }
    }

    /** Accusé de réception du téléphone (service worker), sans connexion : le jeton suffit. */
    public void markDeliveredByReceipt(String token) {
        if (token == null || !RECEIPT_TOKEN.matcher(token).matches()) {
            return;
        }
        if (appNotificationRepository.markDeliveredByReceiptToken(token, Instant.now()) > 0) {
            LOG.info("📲 Notification reçue par le téléphone : SMS de secours annulé");
        }
    }

    /** L'app est ouverte à l'écran : toutes les notifications en attente de l'utilisateur sont reçues. */
    public void markDeliveredForUser(String login) {
        userRepository.findOneByLogin(login).ifPresent(user -> {
            int avoided = appNotificationRepository.markPendingDeliveredForUser(user.getId(), Instant.now());
            if (avoided > 0) {
                LOG.info("📲 {} notification(s) vue(s) dans l'app : SMS de secours annulé(s)", avoided);
            }
        });
    }

    /**
     * Envoie les SMS de secours arrivés à échéance. Chaque notification est réservée avant l'envoi : un accusé de
     * réception arrivé entre-temps l'emporte, et un SMS n'est jamais envoyé deux fois.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 30_000)
    public void sendDueSmsFallbacks() {
        Instant now = Instant.now();
        List<AppNotification> due = appNotificationRepository.findTop50BySmsStatusAndSmsDueAtLessThanEqualOrderBySmsDueAtAsc(
            SmsFallbackStatus.PENDING,
            now
        );
        for (AppNotification notification : due) {
            if (notification.getSmsDueAt().isBefore(now.minus(MAX_SMS_LATENESS))) {
                appNotificationRepository.updateSmsStatus(notification.getId(), SmsFallbackStatus.PENDING, SmsFallbackStatus.EXPIRED);
                continue;
            }
            if (
                appNotificationRepository.updateSmsStatus(notification.getId(), SmsFallbackStatus.PENDING, SmsFallbackStatus.SENDING) == 0
            ) {
                continue;
            }
            boolean sent = false;
            try {
                sent = smsService.sendSms(notification.getSmsPhone(), notification.getSmsMessage());
            } catch (Exception e) {
                LOG.warn("Impossible d'envoyer le SMS de secours de la notification {} : {}", notification.getId(), e.getMessage());
            }
            appNotificationRepository.updateSmsStatus(
                notification.getId(),
                SmsFallbackStatus.SENDING,
                sent ? SmsFallbackStatus.SENT : SmsFallbackStatus.FAILED
            );
            LOG.info("✉️ Notification {} non reçue à temps : SMS de secours {}", notification.getId(), sent ? "envoyé" : "en échec");
        }
    }

    /** SMS économisés (notifications reçues à temps) depuis la date donnée : supervision admin. */
    @Transactional(readOnly = true)
    public long countAvoidedSmsSince(Instant since) {
        return appNotificationRepository.countBySmsStatusAndCreatedDateAfter(SmsFallbackStatus.AVOIDED, since);
    }

    private void save(
        User user,
        RecipientRole role,
        NotificationType type,
        String title,
        String message,
        String targetRoute,
        String smsPhone,
        String smsMessage
    ) {
        Instant now = Instant.now();
        AppNotification notification = new AppNotification();
        notification.setUser(user);
        notification.setRecipientRole(role);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setIsRead(false);
        notification.setTargetRoute(targetRoute);
        notification.setCreatedDate(now);
        if (smsPhone != null) {
            notification.setReceiptToken(newReceiptToken());
            notification.setSmsPhone(smsPhone);
            notification.setSmsMessage(smsMessage);
            notification.setSmsDueAt(now.plusSeconds(Math.max(5, applicationProperties.getSms().getFallbackDelaySeconds())));
            notification.setSmsStatus(SmsFallbackStatus.PENDING);
        }
        AppNotification saved = appNotificationRepository.save(notification);
        realtimeEventService.broadcast("NOTIFICATION_CREATED", appNotificationMapper.toDto(saved));
        // Après validation : sinon l'accusé de réception du téléphone pourrait arriver avant que la notification existe
        afterCommit(() -> browserPushService.sendToUser(user, saved));
        LOG.info("🔔 Notification In-App créée [{}] : {}{}", role, title, smsPhone != null ? " (SMS de secours prévu)" : "");
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                }
            );
        } else {
            action.run();
        }
    }

    private static String newReceiptToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
