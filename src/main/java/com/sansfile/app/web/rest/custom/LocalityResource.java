package com.sansfile.app.web.rest.custom;

import com.sansfile.app.security.SecurityUtils;
import com.sansfile.app.service.custom.locality.LocalityException;
import com.sansfile.app.service.custom.locality.LocalityService;
import com.sansfile.app.service.custom.locality.LocalityService.AccountLocality;
import com.sansfile.app.service.custom.locality.LocalityService.AccountLocalityRequest;
import com.sansfile.app.service.custom.locality.LocalityService.LocalityView;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * Localités côté application : liste publique (choix après connexion, sélecteur de l'en-tête) et
 * localité du compte connecté (client ou coiffeur).
 */
@Tag(name = "Localités", description = "Liste des localités et localité du compte connecté")
@RestController
@RequestMapping("/api")
public class LocalityResource {

    private final LocalityService localityService;

    public LocalityResource(LocalityService localityService) {
        this.localityService = localityService;
    }

    /** Localités actives, par nom ; {@code shopAvailable} : la boutique y est ouverte (partenaire actif). */
    @GetMapping("/public/localities")
    public List<LocalityView> localities() {
        return localityService.publicList();
    }

    @GetMapping("/account/locality")
    public AccountLocality myLocality() {
        return localityService.accountLocality(currentLogin());
    }

    /** Choix (ou changement) de la localité : une localité de la liste, ou le nom d'une zone pas encore ouverte. */
    @PutMapping("/account/locality")
    public AccountLocality chooseLocality(@RequestBody AccountLocalityRequest request) {
        return localityService.setAccountLocality(currentLogin(), request);
    }

    private static String currentLogin() {
        return SecurityUtils.getCurrentUserLogin().orElseThrow(() ->
            new LocalityException(LocalityException.Kind.FORBIDDEN, "not-authenticated", "Connectez-vous pour choisir votre localité.")
        );
    }
}
