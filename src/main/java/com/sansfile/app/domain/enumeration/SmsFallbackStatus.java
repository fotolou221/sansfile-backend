package com.sansfile.app.domain.enumeration;

/**
 * SMS de secours d'une notification : il ne part que si la notification n'a pas été reçue à temps.
 */
public enum SmsFallbackStatus {
    /** Notification envoyée dans l'app ; SMS prévu à l'échéance si personne n'en accuse réception. */
    PENDING,
    /** SMS en cours d'envoi. */
    SENDING,
    SENT,
    FAILED,
    /** Notification reçue à temps : SMS économisé. */
    AVOIDED,
    /** Échéance dépassée de trop loin (serveur arrêté) : le SMS, devenu inutile, n'est pas parti. */
    EXPIRED,
}
