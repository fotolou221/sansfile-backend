package com.sansfile.app.service.custom.agent;

/**
 * Refus métier sur un compte agent. Le message (français) est affiché tel quel ; le code permet au
 * client de réagir (ex. {@code password-change-required} : rediriger vers le changement de mot de passe).
 */
public class AgentAccountException extends RuntimeException {

    public enum Kind {
        INVALID,
        CONFLICT,
        FORBIDDEN,
        NOT_FOUND,
    }

    public static final String PASSWORD_CHANGE_REQUIRED = "password-change-required";

    private final Kind kind;
    private final String code;

    public AgentAccountException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static AgentAccountException passwordChangeRequired() {
        return new AgentAccountException(
            Kind.FORBIDDEN,
            PASSWORD_CHANGE_REQUIRED,
            "Choisissez votre mot de passe personnel avant de continuer."
        );
    }

    public Kind getKind() {
        return kind;
    }

    public String getCode() {
        return code;
    }
}
