package com.sansfile.app.web.rest.custom;

import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.security.SecurityUtils;
import com.sansfile.app.service.custom.notification.NotificationDeliveryService;
import com.sansfile.app.service.custom.push.BrowserPushService;
import com.sansfile.app.service.custom.push.PushSubscriptionRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/push")
public class PushSubscriptionResource {

    private final BrowserPushService browserPushService;
    private final UserRepository userRepository;
    private final NotificationDeliveryService notificationDeliveryService;

    public PushSubscriptionResource(
        BrowserPushService browserPushService,
        UserRepository userRepository,
        NotificationDeliveryService notificationDeliveryService
    ) {
        this.browserPushService = browserPushService;
        this.userRepository = userRepository;
        this.notificationDeliveryService = notificationDeliveryService;
    }

    /**
     * Accusé de réception envoyé par le service worker quand la notification arrive sur le téléphone : le SMS de
     * secours ne part pas. Sans connexion (le service worker n'a pas le jeton de session) : le jeton de la
     * notification, aléatoire, suffit. Réponse identique que le jeton soit connu ou non.
     */
    @PostMapping("/receipts/{token}")
    public ResponseEntity<Void> acknowledgeReceipt(@PathVariable("token") String token) {
        notificationDeliveryService.markDeliveredByReceipt(token);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> getPublicKey() {
        String publicKey = browserPushService.getPublicKey();
        return ResponseEntity.ok(Map.of("publicKey", publicKey != null ? publicKey : ""));
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<Void> registerSubscription(@Valid @RequestBody PushSubscriptionRequest request) {
        SecurityUtils.getCurrentUserLogin()
            .flatMap(userRepository::findOneByLogin)
            .ifPresent(user -> browserPushService.registerSubscription(user, request));
        return ResponseEntity.ok().build();
    }
}
