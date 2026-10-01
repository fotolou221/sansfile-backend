package com.sansfile.app.service.custom.access;

import com.sansfile.app.domain.AppNotification;
import com.sansfile.app.domain.CoiffeurProfile;
import com.sansfile.app.domain.Ticket;
import com.sansfile.app.repository.AppNotificationRepository;
import com.sansfile.app.repository.CoiffeurProfileRepository;
import com.sansfile.app.repository.TicketRepository;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.security.SecurityUtils;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contrôles d'accès métier (propriétaire d'un ticket, coiffeur d'un salon, destinataire d'une notification).
 * Chaque méthode {@code assert…} lève une {@link AccessDeniedException} (HTTP 403) si l'accès est refusé.
 */
@Service
@Transactional(readOnly = true)
public class AccessControlService {

    private final CoiffeurProfileRepository coiffeurProfileRepository;
    private final TicketRepository ticketRepository;
    private final AppNotificationRepository appNotificationRepository;

    public AccessControlService(
        CoiffeurProfileRepository coiffeurProfileRepository,
        TicketRepository ticketRepository,
        AppNotificationRepository appNotificationRepository
    ) {
        this.coiffeurProfileRepository = coiffeurProfileRepository;
        this.ticketRepository = ticketRepository;
        this.appNotificationRepository = appNotificationRepository;
    }

    public boolean isAdmin() {
        return SecurityUtils.hasCurrentUserAnyOfAuthorities(AuthoritiesConstants.ADMIN, AuthoritiesConstants.SUPER_ADMIN);
    }

    /** Identifiant du salon géré par le coiffeur connecté, s'il y en a un. */
    public Optional<Long> currentCoiffeurSalonId() {
        if (!SecurityUtils.hasCurrentUserThisAuthority(AuthoritiesConstants.COIFFEUR)) {
            return Optional.empty();
        }
        return SecurityUtils.getCurrentUserLogin()
            .flatMap(coiffeurProfileRepository::findOneWithSalonByUserLogin)
            .map(CoiffeurProfile::getSalon)
            .map(salon -> salon.getId());
    }

    public boolean isCoiffeurOfSalon(Long salonId) {
        return (
            salonId != null &&
            currentCoiffeurSalonId()
                .map(id -> id.equals(salonId))
                .orElse(false)
        );
    }

    /** Admin, ou coiffeur propriétaire du salon. */
    public void assertCanManageSalon(Long salonId) {
        if (!isAdmin() && !isCoiffeurOfSalon(salonId)) {
            throw new AccessDeniedException("Vous ne gérez pas ce salon.");
        }
    }

    /** Admin, ou coiffeur du salon du ticket (appeler, servir, file d'attente). */
    public void assertCanManageTicket(Long ticketId) {
        if (isAdmin()) {
            return; // ticket inexistant : 404 renvoyé par la ressource
        }
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new AccessDeniedException("Ticket introuvable."));
        Long salonId = ticket.getSalon() != null ? ticket.getSalon().getId() : null;
        assertCanManageSalon(salonId);
    }

    /** Admin, coiffeur du salon du ticket, ou client titulaire du ticket (consulter, annuler). */
    public void assertCanAccessTicket(Long ticketId) {
        if (isAdmin()) {
            return; // ticket inexistant : 404 renvoyé par la ressource
        }
        // Hors admin : 403 même si le ticket n'existe pas (ne révèle pas les identifiants existants)
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new AccessDeniedException("Ticket introuvable."));
        String login = SecurityUtils.getCurrentUserLogin().orElse(null);
        boolean isOwner = login != null && ticket.getUser() != null && Objects.equals(ticket.getUser().getLogin(), login);
        Long salonId = ticket.getSalon() != null ? ticket.getSalon().getId() : null;
        if (!isOwner && !isCoiffeurOfSalon(salonId)) {
            throw new AccessDeniedException("Ce ticket ne vous appartient pas.");
        }
    }

    /** Admin, ou destinataire de la notification. */
    public void assertCanAccessNotification(Long notificationId) {
        if (isAdmin()) {
            return; // notification inexistante : 404 renvoyé par la ressource
        }
        AppNotification notification = appNotificationRepository
            .findById(notificationId)
            .orElseThrow(() -> new AccessDeniedException("Notification introuvable."));
        String login = SecurityUtils.getCurrentUserLogin().orElse(null);
        if (login == null || notification.getUser() == null || !Objects.equals(notification.getUser().getLogin(), login)) {
            throw new AccessDeniedException("Cette notification ne vous appartient pas.");
        }
    }
}
