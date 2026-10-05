package com.sansfile.app.web.rest;

import static com.sansfile.app.security.SecurityUtils.AUTHORITIES_CLAIM;
import static com.sansfile.app.security.SecurityUtils.JWT_ALGORITHM;
import static com.sansfile.app.security.SecurityUtils.TOKEN_TYPE_CLAIM;
import static com.sansfile.app.security.SecurityUtils.USER_ID_CLAIM;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.security.DomainUserDetailsService.UserWithId;
import com.sansfile.app.security.UserNotActivatedException;
import com.sansfile.app.service.custom.access.LoginAttemptService;
import com.sansfile.app.service.custom.agent.AgentActivityService;
import com.sansfile.app.web.rest.vm.LoginVM;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.*;

/**
 * Controller to authenticate users.
 */
@Tag(name = "1. Authentification & OTP", description = "Authentification JWT administrateur et tokens de session")
@RestController
@RequestMapping("/api")
public class AuthenticateController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticateController.class);

    private final JwtEncoder jwtEncoder;

    @Value("${jhipster.security.authentication.jwt.token-validity-in-seconds:900}")
    private long tokenValidityInSeconds;

    @Value("${jhipster.security.authentication.jwt.token-validity-in-seconds-for-remember-me:5184000}")
    private long tokenValidityInSecondsForRememberMe;

    private final AuthenticationManagerBuilder authenticationManagerBuilder;

    private final LoginAttemptService loginAttemptService;

    /** Journal des agents ; absent du contexte réduit des tests d'authentification (pas de JPA). */
    private final ObjectProvider<AgentActivityService> agentActivityService;

    public AuthenticateController(
        JwtEncoder jwtEncoder,
        AuthenticationManagerBuilder authenticationManagerBuilder,
        LoginAttemptService loginAttemptService,
        ObjectProvider<AgentActivityService> agentActivityService
    ) {
        this.jwtEncoder = jwtEncoder;
        this.authenticationManagerBuilder = authenticationManagerBuilder;
        this.loginAttemptService = loginAttemptService;
        this.agentActivityService = agentActivityService;
    }

    @PostMapping("/authenticate")
    public ResponseEntity<JWTToken> authorize(@Valid @RequestBody LoginVM loginVM) {
        String login = loginVM.getUsername();
        if (loginAttemptService.isLocked(login)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        var authenticationToken = new UsernamePasswordAuthenticationToken(login, loginVM.getPassword());
        Authentication authentication;
        try {
            authentication = authenticationManagerBuilder.getObject().authenticate(authenticationToken);
        } catch (AuthenticationException e) {
            loginAttemptService.recordFailure(login);
            boolean disabled =
                e instanceof DisabledException ||
                e instanceof UserNotActivatedException ||
                e.getCause() instanceof UserNotActivatedException;
            String reason = disabled ? "compte désactivé" : "mot de passe incorrect";
            agentActivityService.ifAvailable(journal -> journal.recordFailedLogin(login, reason));
            if (disabled) {
                // 401 comme un mauvais mot de passe (sinon 500) : ne pas révéler qu'un compte désactivé existe
                throw new BadCredentialsException("Bad credentials");
            }
            throw e;
        }
        // Mot de passe réservé à la console d'administration et aux agents de terrain :
        // clients et coiffeurs se connectent par SMS
        Set<String> roles = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        boolean isAdmin = roles.contains(AuthoritiesConstants.ADMIN) || roles.contains(AuthoritiesConstants.SUPER_ADMIN);
        boolean isAgent = roles.contains(AuthoritiesConstants.AGENT);
        if (!isAdmin && !isAgent) {
            loginAttemptService.recordFailure(login);
            throw new BadCredentialsException("Bad credentials");
        }
        loginAttemptService.recordSuccess(login);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        if (isAgent && !isAdmin && authentication.getPrincipal() instanceof UserWithId agent) {
            agentActivityService.ifAvailable(journal -> journal.recordLogin(agent.getId(), agent.getUsername()));
        }
        String jwt = this.createToken(authentication);
        String refreshToken = this.createRefreshToken(authentication);
        var httpHeaders = new HttpHeaders();
        httpHeaders.setBearerAuth(jwt);
        return new ResponseEntity<>(new JWTToken(jwt, refreshToken), httpHeaders, HttpStatus.OK);
    }

    /**
     * {@code GET /authenticate} : check if the user is authenticated.
     *
     * @return the {@link ResponseEntity} with status {@code 204 (No Content)},
     * or with status {@code 401 (Unauthorized)} if not authenticated.
     */
    @GetMapping("/authenticate")
    public ResponseEntity<Void> isAuthenticated(Principal principal) {
        LOG.debug("REST request to check if the current user is authenticated");
        return ResponseEntity.status(principal == null ? HttpStatus.UNAUTHORIZED : HttpStatus.NO_CONTENT).build();
    }

    public String createToken(Authentication authentication) {
        String authorities = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.joining(" "));

        var now = Instant.now();
        Instant validity = now.plus(this.tokenValidityInSeconds, ChronoUnit.SECONDS);

        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(validity)
            .subject(authentication.getName())
            .claim(AUTHORITIES_CLAIM, authorities)
            .claim(TOKEN_TYPE_CLAIM, "ACCESS");
        if (authentication.getPrincipal() instanceof UserWithId user) {
            builder.claim(USER_ID_CLAIM, user.getId());
        }

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return this.jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, builder.build())).getTokenValue();
    }

    public String createRefreshToken(Authentication authentication) {
        var now = Instant.now();
        Instant validity = now.plus(this.tokenValidityInSecondsForRememberMe, ChronoUnit.SECONDS);

        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(validity)
            .subject(authentication.getName())
            .claim(TOKEN_TYPE_CLAIM, "REFRESH");
        if (authentication.getPrincipal() instanceof UserWithId user) {
            builder.claim(USER_ID_CLAIM, user.getId());
        }

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return this.jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, builder.build())).getTokenValue();
    }

    /**
     * Object to return as body in JWT Authentication.
     */
    static class JWTToken {

        private String idToken;
        private String refreshToken;

        JWTToken(String idToken, String refreshToken) {
            this.idToken = idToken;
            this.refreshToken = refreshToken;
        }

        @JsonProperty("id_token")
        String getIdToken() {
            return idToken;
        }

        @JsonProperty("token")
        String getToken() {
            return idToken;
        }

        @JsonProperty("refresh_token")
        String getRefreshToken() {
            return refreshToken;
        }

        @JsonProperty("refreshToken")
        String getAltRefreshToken() {
            return refreshToken;
        }

        void setIdToken(String idToken) {
            this.idToken = idToken;
        }

        void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }
    }
}
