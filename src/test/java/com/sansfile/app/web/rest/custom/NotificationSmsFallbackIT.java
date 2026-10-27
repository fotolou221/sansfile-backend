package com.sansfile.app.web.rest.custom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sansfile.app.IntegrationTest;
import com.sansfile.app.domain.AppNotification;
import com.sansfile.app.domain.User;
import com.sansfile.app.domain.enumeration.NotificationType;
import com.sansfile.app.domain.enumeration.RecipientRole;
import com.sansfile.app.domain.enumeration.SmsFallbackStatus;
import com.sansfile.app.repository.AppNotificationRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.service.custom.notification.NotificationDeliveryService;
import com.sansfile.app.service.custom.sms.SmsUsageTracker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Hors code de connexion, l'alerte part dans l'app ; le SMS seulement si ni l'app ni le téléphone n'en accusent
 * réception à temps. Non transactionnel : l'envoi des SMS de secours travaille dans ses propres transactions.
 */
@AutoConfigureMockMvc
@IntegrationTest
class NotificationSmsFallbackIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private AppNotificationRepository appNotificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SmsUsageTracker smsUsageTracker;

    private final List<User> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (User user : createdUsers) {
            appNotificationRepository.deleteAll(appNotificationRepository.findByUserLoginOrderByCreatedDateDesc(user.getLogin()));
            userRepository.delete(user);
        }
    }

    @Test
    void receiptFromThePhoneCancelsTheSms() throws Exception {
        User client = createClient();
        AppNotification notification = notifyWithFallback(client);
        assertThat(notification.getSmsStatus()).isEqualTo(SmsFallbackStatus.PENDING);
        assertThat(notification.getReceiptToken()).hasSizeGreaterThanOrEqualTo(16);

        // Service worker du téléphone : sans session
        mockMvc.perform(post("/api/push/receipts/" + notification.getReceiptToken())).andExpect(status().isNoContent());
        moveDueDate(notification, Instant.now().minusSeconds(1));
        deliveryService.sendDueSmsFallbacks();

        AppNotification reloaded = reload(notification);
        assertThat(reloaded.getSmsStatus()).isEqualTo(SmsFallbackStatus.AVOIDED);
        assertThat(reloaded.getDeliveredAt()).isNotNull();
    }

    @Test
    void unknownReceiptTokenIsAnsweredLikeAKnownOne() throws Exception {
        mockMvc.perform(post("/api/push/receipts/inconnu-0123456789abcdef")).andExpect(status().isNoContent());
    }

    @Test
    void openAppCancelsTheSms() throws Exception {
        User client = createClient();
        AppNotification notification = notifyWithFallback(client);

        mockMvc.perform(put("/api/notifications/mark-delivered").with(user(client.getLogin()))).andExpect(status().isNoContent());

        assertThat(reload(notification).getSmsStatus()).isEqualTo(SmsFallbackStatus.AVOIDED);
    }

    @Test
    void smsLeavesWhenNobodyAcknowledgesInTime() {
        User client = createClient();
        AppNotification notification = notifyWithFallback(client);
        long sentBefore = smsUsageTracker.snapshot().sent();

        moveDueDate(notification, Instant.now().minusSeconds(1));
        deliveryService.sendDueSmsFallbacks();

        assertThat(reload(notification).getSmsStatus()).isEqualTo(SmsFallbackStatus.SENT);
        assertThat(smsUsageTracker.snapshot().sent()).isGreaterThan(sentBefore);
    }

    @Test
    void smsTooLateIsDropped() {
        User client = createClient();
        AppNotification notification = notifyWithFallback(client);

        moveDueDate(notification, Instant.now().minus(Duration.ofMinutes(20)));
        deliveryService.sendDueSmsFallbacks();

        assertThat(reload(notification).getSmsStatus()).isEqualTo(SmsFallbackStatus.EXPIRED);
    }

    @Test
    void phoneWithAnAccountGetsANotificationFirst() {
        User client = createClient();
        long sentBefore = smsUsageTracker.snapshot().sent();

        deliveryService.notifyPhone(
            client.getLogin(),
            NotificationType.TICKET,
            "C'est votre tour !",
            "Au fauteuil",
            "/client/tickets",
            "SMS"
        );

        List<AppNotification> notifications = appNotificationRepository.findByUserLoginOrderByCreatedDateDesc(client.getLogin());
        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst().getSmsStatus()).isEqualTo(SmsFallbackStatus.PENDING);
        assertThat(smsUsageTracker.snapshot().sent()).isEqualTo(sentBefore);
    }

    @Test
    void phoneWithoutAccountGetsTheSmsRightAway() {
        long sentBefore = smsUsageTracker.snapshot().sent();

        deliveryService.notifyPhone(randomPhone(), NotificationType.TICKET, "C'est votre tour !", "Au fauteuil", "/client/tickets", "SMS");

        assertThat(smsUsageTracker.snapshot().sent()).isGreaterThan(sentBefore);
    }

    private AppNotification notifyWithFallback(User client) {
        deliveryService.notifyWithSmsFallback(
            client,
            RecipientRole.CLIENT,
            NotificationType.TICKET,
            "C'est votre tour ! ✂️",
            "Votre coiffeur vous attend.",
            "/client/tickets",
            client.getLogin(),
            "SansFile : C'est bientôt votre tour."
        );
        return appNotificationRepository.findByUserLoginOrderByCreatedDateDesc(client.getLogin()).getFirst();
    }

    private void moveDueDate(AppNotification notification, Instant dueAt) {
        AppNotification current = reload(notification);
        current.setSmsDueAt(dueAt);
        appNotificationRepository.saveAndFlush(current);
    }

    private AppNotification reload(AppNotification notification) {
        return appNotificationRepository.findById(notification.getId()).orElseThrow();
    }

    private User createClient() {
        String phone = randomPhone();
        User user = new User();
        user.setLogin(phone);
        user.setPhone(phone);
        user.setPassword("$2a$10$" + "x".repeat(53));
        user.setFirstName("Client");
        user.setLastName("Test");
        user.setEmail(phone.substring(1) + "@test.sansfile.sn");
        user.setActivated(true);
        user.setLangKey("fr");
        User saved = userRepository.saveAndFlush(user);
        createdUsers.add(saved);
        return saved;
    }

    private static String randomPhone() {
        return "+22177" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    }
}
