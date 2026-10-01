package com.sansfile.app.service.custom.realtime.impl;

import com.sansfile.app.service.custom.realtime.RealtimeEventService;
import com.sansfile.app.service.dto.BoutiqueOrderDTO;
import com.sansfile.app.service.dto.TicketDTO;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Implémentation du service de diffusion temps réel par Server-Sent Events (SSE).
 */
@Service
public class RealtimeEventServiceImpl implements RealtimeEventService {

    private static final Logger LOG = LoggerFactory.getLogger(RealtimeEventServiceImpl.class);
    private static final Long SSE_TIMEOUT = 24 * 60 * 60 * 1000L; // 24 heures
    /** Limite de connexions simultanées : évite l'épuisement mémoire par ouverture massive de flux. */
    private static final int MAX_CLIENTS = 5000;

    /** Événements dont le contenu est public (déjà lisible via l'API sans connexion). */
    private static final Set<String> PUBLIC_EVENTS = Set.of(
        "SALON_CREATED",
        "SALON_UPDATED",
        "SALON_DELETED",
        "PRODUCT_CREATED",
        "PRODUCT_UPDATED",
        "PRODUCT_DELETED",
        "CATEGORY_CREATED",
        "CATEGORY_UPDATED",
        "CATEGORY_DELETED",
        "SETTINGS_UPDATED"
    );

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @Override
    public int connectedClients() {
        return emitters.size();
    }

    @Override
    public SseEmitter registerClient() {
        if (emitters.size() >= MAX_CLIENTS) {
            throw new IllegalStateException("Trop de connexions temps réel simultanées.");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        emitters.add(emitter);
        LOG.debug("🔌 Nouveau client temps réel connecté. Clients actifs : {}", emitters.size());

        try {
            emitter.send(
                SseEmitter.event().name("CONNECTED").data(Map.of("message", "Connexion temps réel SansFile établie avec succès."))
            );
        } catch (IOException e) {
            emitters.remove(emitter);
        }

        return emitter;
    }

    @Override
    public void broadcast(String eventType, Object payload) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        doBroadcast(eventType, publicPayload(eventType, payload));
                    }
                }
            );
        } else {
            doBroadcast(eventType, publicPayload(eventType, payload));
        }
    }

    /**
     * Le flux temps réel est public : tickets, commandes et notifications contiennent des données
     * personnelles (noms, téléphones, adresses). On ne diffuse que l'identifiant ou le compteur utile ;
     * chaque client recharge ensuite ses propres données via l'API authentifiée.
     */
    static Object publicPayload(String eventType, Object payload) {
        if (PUBLIC_EVENTS.contains(eventType)) {
            return payload;
        }
        Map<String, Object> safe = new HashMap<>();
        if ("QUEUE_UPDATED".equals(eventType) && payload instanceof List<?> queue) {
            safe.put("waitingCount", queue.size());
            if (!queue.isEmpty() && queue.getFirst() instanceof TicketDTO first && first.getSalon() != null) {
                safe.put("salonId", first.getSalon().getId());
            }
        } else if (eventType.startsWith("ORDER_")) {
            if (payload instanceof BoutiqueOrderDTO order) {
                safe.put("id", order.getId());
            } else if (payload instanceof Map<?, ?> map && map.get("id") != null) {
                safe.put("id", map.get("id"));
            }
        }
        return safe;
    }

    private void doBroadcast(String eventType, Object payload) {
        if (emitters.isEmpty()) {
            return;
        }

        LOG.info("⚡ [REALTIME BROADCAST] Événement '{}' diffusé à {} clients connectés", eventType, emitters.size());
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventType).data(payload));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
            LOG.debug("🧹 Nettoyage de {} connexions expirées.", deadEmitters.size());
        }
    }

    /**
     * Heartbeat régulier toutes les 15 secondes pour maintenir la connexion active à travers les proxies et routeurs.
     */
    @Scheduled(fixedRate = 15000)
    public void sendHeartbeat() {
        if (emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping").name("PING").data("keep-alive"));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
        }
    }
}
