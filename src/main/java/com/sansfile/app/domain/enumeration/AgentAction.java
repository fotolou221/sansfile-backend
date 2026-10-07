package com.sansfile.app.domain.enumeration;

/**
 * Événements du journal des agents de terrain : leurs actions et celles de l'admin sur leur compte.
 */
public enum AgentAction {
    ACCOUNT_CREATED,
    ACCOUNT_UPDATED,
    ACCOUNT_DISABLED,
    ACCOUNT_ENABLED,
    PASSWORD_RESET,
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    PASSWORD_CHANGED,
    SALON_CREATED,
    SALON_UPDATED,
    LOCALITIES_UPDATED,
}
