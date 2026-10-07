package com.sansfile.app.service.custom.locality;

/**
 * Refus métier sur les localités, partenaires et commandes par localité. Le message (français) est
 * affiché tel quel ; le code permet au client de réagir (ex. {@code shop-unavailable} : boutique
 * pas encore ouverte dans la localité).
 */
public class LocalityException extends RuntimeException {

    public enum Kind {
        INVALID,
        CONFLICT,
        FORBIDDEN,
        NOT_FOUND,
    }

    private final Kind kind;
    private final String code;

    public LocalityException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static LocalityException invalid(String code, String message) {
        return new LocalityException(Kind.INVALID, code, message);
    }

    public static LocalityException conflict(String code, String message) {
        return new LocalityException(Kind.CONFLICT, code, message);
    }

    public static LocalityException notFound(String code, String message) {
        return new LocalityException(Kind.NOT_FOUND, code, message);
    }

    public Kind getKind() {
        return kind;
    }

    public String getCode() {
        return code;
    }
}
