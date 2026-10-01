package com.sansfile.app.config;

import static com.sansfile.app.security.SecurityUtils.JWT_ALGORITHM;
import static com.sansfile.app.security.SecurityUtils.TOKEN_TYPE_CLAIM;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import com.sansfile.app.management.SecurityMetersService;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

@Configuration
public class SecurityJwtConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityJwtConfiguration.class);

    /** HS512 : clé d'au moins 512 bits. */
    private static final int MIN_KEY_BYTES = 64;

    // Aucune valeur par défaut dans le code : le secret vient de la configuration (JWT_SECRET en production)
    @Value("${jhipster.security.authentication.jwt.base64-secret:${JWT_SECRET:}}")
    private String jwtKey;

    /** Décodeur des jetons d'accès de l'API : un jeton de rafraîchissement n'y est jamais accepté. */
    @Bean
    @Primary
    public JwtDecoder jwtDecoder(SecurityMetersService metersService) {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(getSecretKey()).macAlgorithm(JWT_ALGORITHM).build();
        return token -> {
            Jwt jwt;
            try {
                jwt = jwtDecoder.decode(token);
            } catch (Exception e) {
                if (e.getMessage().contains("Invalid signature")) {
                    metersService.trackTokenInvalidSignature();
                } else if (e.getMessage().contains("Jwt expired at")) {
                    metersService.trackTokenExpired();
                } else if (
                    e.getMessage().contains("Invalid JWT serialization") ||
                    e.getMessage().contains("Malformed token") ||
                    e.getMessage().contains("Invalid unsecured/JWS/JWE")
                ) {
                    metersService.trackTokenMalformed();
                } else {
                    LOG.error("Unknown JWT error {}", e.getMessage());
                }
                throw e;
            }
            if ("REFRESH".equals(jwt.getClaimAsString(TOKEN_TYPE_CLAIM))) {
                throw new BadJwtException("Un jeton de rafraîchissement ne peut pas servir de jeton d'accès.");
            }
            return jwt;
        };
    }

    /** Décodeur réservé à /api/auth/refresh : le service vérifie ensuite que le jeton est bien de type REFRESH. */
    @Bean
    public JwtDecoder refreshTokenDecoder() {
        return NimbusJwtDecoder.withSecretKey(getSecretKey()).macAlgorithm(JWT_ALGORITHM).build();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(getSecretKey()));
    }

    /** Jeton uniquement dans l'en-tête Authorization : jamais dans l'URL (fuite dans les logs et l'historique). */
    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        return new DefaultBearerTokenResolver();
    }

    private SecretKey getSecretKey() {
        if (jwtKey == null || jwtKey.isBlank()) {
            throw new IllegalStateException(
                "Secret JWT manquant : définissez la variable d'environnement JWT_SECRET (base64, 64 octets minimum)."
            );
        }
        byte[] keyBytes = Base64.from(jwtKey.trim()).decode();
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("Secret JWT trop court : " + keyBytes.length + " octets, " + MIN_KEY_BYTES + " minimum.");
        }
        return new SecretKeySpec(keyBytes, 0, keyBytes.length, JWT_ALGORITHM.getName());
    }
}
