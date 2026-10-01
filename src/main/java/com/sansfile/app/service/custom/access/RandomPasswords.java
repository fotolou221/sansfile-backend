package com.sansfile.app.service.custom.access;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mot de passe aléatoire pour les comptes qui se connectent uniquement par SMS (jamais communiqué).
 * 32 octets encodés = 43 caractères : sous la limite de 72 octets de BCrypt.
 */
public final class RandomPasswords {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomPasswords() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
