package com.sansfile.app.security;

/**
 * Constants for Spring Security authorities.
 */
public final class AuthoritiesConstants {

    public static final String ADMIN = "ROLE_ADMIN";

    public static final String SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    public static final String USER = "ROLE_USER";

    public static final String CLIENT = "ROLE_CLIENT";

    public static final String COIFFEUR = "ROLE_COIFFEUR";

    /** Agent de terrain : connexion e-mail + mot de passe, inscrit des salons pour SansFile. */
    public static final String AGENT = "ROLE_AGENT";

    public static final String ANONYMOUS = "ROLE_ANONYMOUS";

    private AuthoritiesConstants() {}
}
