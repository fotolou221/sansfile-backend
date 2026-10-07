package com.sansfile.app.service.custom.auth.impl;

import static com.sansfile.app.security.SecurityUtils.AUTHORITIES_CLAIM;
import static com.sansfile.app.security.SecurityUtils.JWT_ALGORITHM;
import static com.sansfile.app.security.SecurityUtils.TOKEN_TYPE_CLAIM;
import static com.sansfile.app.security.SecurityUtils.USER_ID_CLAIM;

import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.domain.Authority;
import com.sansfile.app.domain.User;
import com.sansfile.app.repository.AuthorityRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.service.custom.auth.AuthOtpService;
import com.sansfile.app.service.custom.locality.LocalityService;
import com.sansfile.app.service.custom.otp.OtpService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implémentation du service d'authentification OTP et émission de JWT.
 */
@Service
@Transactional
public class AuthOtpServiceImpl implements AuthOtpService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthOtpServiceImpl.class);

    private final OtpService otpService;
    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final com.sansfile.app.repository.CoiffeurProfileRepository coiffeurProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final ApplicationProperties applicationProperties;
    private final LocalityService localityService;

    @Value("${jhipster.security.authentication.jwt.token-validity-in-seconds:900}")
    private long tokenValidityInSeconds;

    @Value("${jhipster.security.authentication.jwt.token-validity-in-seconds-for-remember-me:5184000}")
    private long refreshTokenValidityInSeconds;

    public AuthOtpServiceImpl(
        OtpService otpService,
        UserRepository userRepository,
        AuthorityRepository authorityRepository,
        com.sansfile.app.repository.CoiffeurProfileRepository coiffeurProfileRepository,
        PasswordEncoder passwordEncoder,
        JwtEncoder jwtEncoder,
        @org.springframework.beans.factory.annotation.Qualifier("refreshTokenDecoder") JwtDecoder jwtDecoder,
        ApplicationProperties applicationProperties,
        LocalityService localityService
    ) {
        this.localityService = localityService;
        this.otpService = otpService;
        this.userRepository = userRepository;
        this.authorityRepository = authorityRepository;
        this.coiffeurProfileRepository = coiffeurProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.applicationProperties = applicationProperties;
    }

    @Override
    public SendOtpResult sendOtp(String rawPhone, String role) {
        String normalizedPhone = otpService.normalizePhoneNumber(rawPhone);
        otpService.generateAndSendOtp(normalizedPhone);

        int expiration = applicationProperties.getOtp().getExpirationSeconds();
        int cooldown = applicationProperties.getOtp().getResendCooldownSeconds();

        return new SendOtpResult(normalizedPhone, expiration, cooldown, "Code de vérification envoyé avec succès par SMS.");
    }

    @Override
    public AuthResult verifyOtp(String rawPhone, String code, String role, String fullName) {
        String normalizedPhone = otpService.normalizePhoneNumber(rawPhone);
        OtpService.VerificationResult check = otpService.verifyOtp(normalizedPhone, code);

        if (!check.isValid()) {
            throw new BadCredentialsException(otpFailureMessage(check));
        }

        User user = findOrCreateUser(normalizedPhone, fullName);
        if (!user.isActivated()) {
            throw new BadCredentialsException("Ce compte est désactivé.");
        }
        AuthUserProfile profile = buildProfile(user, role);
        if ("admin".equals(profile.role())) {
            // Les droits d'administration ne s'obtiennent jamais par SMS : mot de passe sur la console uniquement
            throw new BadCredentialsException("Les administrateurs se connectent depuis la console d'administration.");
        }
        if ("agent".equals(profile.role())) {
            throw new BadCredentialsException("Les agents de terrain se connectent avec leur e-mail et leur mot de passe (espace agent).");
        }
        String accessToken = createAccessToken(user, profile.role());
        String refreshToken = createRefreshToken(user, profile.role());

        return new AuthResult(accessToken, accessToken, refreshToken, profile);
    }

    /** Message affiché tel quel sur la page du code SMS. */
    private static String otpFailureMessage(OtpService.VerificationResult check) {
        return switch (check.status()) {
            case EXPIRED -> "Ce code a expiré. Demandez un nouveau code.";
            case TOO_MANY_ATTEMPTS -> "Trop de codes incorrects. Demandez un nouveau code.";
            case WRONG_CODE -> check.remainingAttempts() == 1
                ? "Code incorrect. Il vous reste 1 tentative."
                : "Code incorrect. Il vous reste " + check.remainingAttempts() + " tentatives.";
            default -> "Aucun code en cours pour ce numéro. Demandez un nouveau code.";
        };
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResult refreshToken(String refreshTokenStr) {
        if (refreshTokenStr == null || refreshTokenStr.isBlank()) {
            throw new BadCredentialsException("Jeton de rafraîchissement absent.");
        }
        try {
            Jwt jwt = this.jwtDecoder.decode(refreshTokenStr);
            String tokenType = jwt.getClaimAsString(TOKEN_TYPE_CLAIM);
            if (!"REFRESH".equals(tokenType)) {
                throw new BadCredentialsException("Type de jeton invalide (attendu: REFRESH).");
            }
            String login = jwt.getSubject();
            if (login == null) {
                throw new BadCredentialsException("Identifiant utilisateur manquant dans le jeton.");
            }

            User user = userRepository
                .findOneWithAuthoritiesByLogin(login)
                .orElseThrow(() -> new BadCredentialsException("Utilisateur associé au jeton introuvable."));

            if (!Boolean.TRUE.equals(user.isActivated())) {
                throw new BadCredentialsException("Le compte utilisateur est désactivé.");
            }

            String requestedRole = jwt.getClaimAsString("role");
            AuthUserProfile profile = buildProfile(user, requestedRole);
            String newAccessToken = createAccessToken(user, profile.role());
            String newRefreshToken = createRefreshToken(user, profile.role());

            LOG.debug("🔄 Jeton rafraîchi pour l'utilisateur : {}", login);
            return new AuthResult(newAccessToken, newAccessToken, newRefreshToken, profile);
        } catch (JwtException e) {
            LOG.warn("Échec du décodage du Refresh Token : {}", e.getMessage());
            throw new BadCredentialsException("Jeton de rafraîchissement invalide ou expiré : " + e.getMessage());
        }
    }

    private String createAccessToken(User user, String roleClean) {
        String authorities = resolveTokenAuthorities(user, roleClean);
        Instant now = Instant.now();
        Instant validity = now.plus(tokenValidityInSeconds, ChronoUnit.SECONDS);

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(validity)
            .subject(user.getLogin())
            .claim(AUTHORITIES_CLAIM, authorities)
            .claim(USER_ID_CLAIM, user.getId())
            .claim(TOKEN_TYPE_CLAIM, "ACCESS")
            .claim("role", roleClean)
            .build();

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return this.jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    private String resolveTokenAuthorities(User user, String roleClean) {
        // Admin et agent de terrain : leurs vrais rôles (sinon un agent redeviendrait « client » au renouvellement)
        if ("admin".equals(roleClean) || "agent".equals(roleClean)) {
            return user.getAuthorities().stream().map(Authority::getName).collect(Collectors.joining(" "));
        }

        String primaryRole = "coiffeur".equals(roleClean) ? AuthoritiesConstants.COIFFEUR : AuthoritiesConstants.CLIENT;
        return AuthoritiesConstants.USER + " " + primaryRole;
    }

    private String createRefreshToken(User user, String roleClean) {
        Instant now = Instant.now();
        Instant validity = now.plus(refreshTokenValidityInSeconds, ChronoUnit.SECONDS);

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(validity)
            .subject(user.getLogin())
            .claim(USER_ID_CLAIM, user.getId())
            .claim(TOKEN_TYPE_CLAIM, "REFRESH")
            .claim("role", roleClean)
            .build();

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return this.jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    private AuthUserProfile buildProfile(User user, String requestedRole) {
        Optional<com.sansfile.app.domain.CoiffeurProfile> optProfile = coiffeurProfileRepository.findOneWithSalonByUserLogin(
            user.getLogin()
        );

        if (optProfile.isEmpty()) {
            String rawPhone = user.getPhone() != null && !user.getPhone().isBlank() ? user.getPhone() : user.getLogin();
            if (rawPhone != null && !rawPhone.isBlank()) {
                String digitsOnly = rawPhone.replaceAll("[^0-9]", "");
                String local9 = digitsOnly.length() >= 9 ? digitsOnly.substring(digitsOnly.length() - 9) : digitsOnly;
                String intlPhone = "+221" + local9;

                optProfile = coiffeurProfileRepository
                    .findByPhone(intlPhone)
                    .or(() -> coiffeurProfileRepository.findByPhone(local9))
                    .or(() -> coiffeurProfileRepository.findByPhone(rawPhone));
            }
        }

        if (optProfile.isPresent() && optProfile.get().getUser() == null) {
            optProfile.get().setUser(user);
            try {
                coiffeurProfileRepository.save(optProfile.get());
            } catch (Exception e) {
                LOG.warn("Impossible de lier l'utilisateur au profil coiffeur: {}", e.getMessage());
            }
        }

        Long salonId = null;
        String salonSlug = null;
        if (optProfile.isPresent() && optProfile.get().getSalon() != null) {
            salonId = optProfile.get().getSalon().getId();
            salonSlug = optProfile.get().getSalon().getSlug();
        }

        boolean hasCoiffeurProfile = optProfile.isPresent();
        boolean hasCoiffeurAuthority =
            user.getAuthorities() != null &&
            user
                .getAuthorities()
                .stream()
                .anyMatch(a -> AuthoritiesConstants.COIFFEUR.equalsIgnoreCase(a.getName()) || "COIFFEUR".equalsIgnoreCase(a.getName()));
        boolean isCoiffeur = hasCoiffeurProfile || hasCoiffeurAuthority;

        boolean isAdmin =
            user.getAuthorities() != null &&
            user
                .getAuthorities()
                .stream()
                .anyMatch(
                    a ->
                        AuthoritiesConstants.ADMIN.equalsIgnoreCase(a.getName()) ||
                        AuthoritiesConstants.SUPER_ADMIN.equalsIgnoreCase(a.getName())
                );

        boolean isAgent =
            user.getAuthorities() != null &&
            user
                .getAuthorities()
                .stream()
                .anyMatch(a -> AuthoritiesConstants.AGENT.equalsIgnoreCase(a.getName()));

        String roleClean;
        if (isAdmin) {
            roleClean = "admin";
        } else if (isAgent) {
            roleClean = "agent";
        } else if (isCoiffeur) {
            roleClean = "coiffeur";
        } else {
            roleClean = "client";
        }

        if (isCoiffeur && !hasCoiffeurAuthority) {
            authorityRepository.findById(AuthoritiesConstants.COIFFEUR).ifPresent(auth -> {
                Set<Authority> authorities = new HashSet<>(user.getAuthorities());
                authorities.add(auth);
                user.setAuthorities(authorities);
                try {
                    userRepository.save(user);
                } catch (Exception e) {
                    LOG.warn("Impossible d'ajouter le rôle COIFFEUR à l'utilisateur : {}", e.getMessage());
                }
            });
        }

        String homeRoute = switch (roleClean) {
            case "admin" -> "/admin/dashboard";
            case "agent" -> "/agent";
            case "coiffeur" -> "/coiffeur/home";
            default -> "/client/home";
        };

        String displayName =
            user.getFirstName() != null && !user.getFirstName().isBlank()
                ? user.getFirstName() + (user.getLastName() != null ? " " + user.getLastName() : "")
                : "coiffeur".equals(roleClean)
                  ? optProfile.isPresent() && optProfile.get().getName() != null && !optProfile.get().getName().isBlank()
                      ? optProfile.get().getName()
                      : "Barbier SansFile"
                  : isAdmin
                    ? "Administrateur SansFile"
                    : isAgent
                      ? "Agent SansFile"
                      : "Client SansFile";

        // Clients et coiffeurs : localité du compte (celle du salon pour un coiffeur dont le salon est rattaché)
        LocalityService.AccountLocality locality =
            "client".equals(roleClean) || "coiffeur".equals(roleClean) ? localityService.accountLocality(user) : null;

        return new AuthUserProfile(
            user.getId(),
            displayName,
            user.getLogin(),
            roleClean,
            homeRoute,
            user.getImageUrl(),
            salonId,
            salonSlug,
            locality != null ? locality.localityId() : null,
            locality != null ? locality.localityName() : null,
            locality == null || locality.chosen()
        );
    }

    private User findOrCreateUser(String phone, String optionalName) {
        String digitsOnly = phone != null ? phone.replaceAll("[^0-9]", "") : "";
        String local9 = digitsOnly.length() >= 9 ? digitsOnly.substring(digitsOnly.length() - 9) : digitsOnly;
        String intlPhone = "+221" + local9;

        Optional<User> optUser = userRepository
            .findOneWithAuthoritiesByLogin(phone)
            .or(() -> userRepository.findOneWithAuthoritiesByPhone(phone))
            .or(() -> userRepository.findOneWithAuthoritiesByPhone(intlPhone))
            .or(() -> userRepository.findOneWithAuthoritiesByPhone(local9))
            .or(() -> userRepository.findOneWithAuthoritiesByLogin(intlPhone))
            .or(() -> userRepository.findOneWithAuthoritiesByLogin(local9));

        if (optUser.isPresent()) {
            User existing = optUser.get();
            if (existing.getPhone() == null || existing.getPhone().isBlank()) {
                existing.setPhone(intlPhone);
                try {
                    return userRepository.save(existing);
                } catch (Exception e) {
                    LOG.warn("Impossible de mettre à jour le téléphone de l'utilisateur existant: {}", e.getMessage());
                }
            }
            return existing;
        }

        User newUser = new User();
        newUser.setLogin(intlPhone);
        newUser.setPhone(intlPhone);
        // Connexion par SMS uniquement : mot de passe aléatoire, jamais communiqué
        newUser.setPassword(passwordEncoder.encode(com.sansfile.app.service.custom.access.RandomPasswords.generate()));
        newUser.setActivated(true);
        newUser.setLangKey("fr");

        if (optionalName != null && !optionalName.isBlank()) {
            String[] parts = optionalName.trim().split(" ", 2);
            newUser.setFirstName(parts[0]);
            if (parts.length > 1) {
                newUser.setLastName(parts[1]);
            }
        } else {
            newUser.setFirstName("Client");
            newUser.setLastName(local9.length() >= 4 ? local9.substring(local9.length() - 4) : local9);
        }

        Set<Authority> authorities = new HashSet<>();
        authorityRepository.findById(AuthoritiesConstants.CLIENT).ifPresent(authorities::add);
        authorityRepository.findById(AuthoritiesConstants.USER).ifPresent(authorities::add);
        newUser.setAuthorities(authorities);

        return userRepository.save(newUser);
    }
}
