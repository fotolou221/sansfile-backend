package com.sansfile.app.web.rest.custom;

import com.sansfile.app.service.custom.locality.PartnerService;
import com.sansfile.app.service.custom.locality.PartnerService.ShopView;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * Boutique côté application : celle du partenaire le plus proche de la position du téléphone. Les clients ne
 * choisissent pas de localité ; la position n'est pas enregistrée.
 */
@Tag(name = "Boutique", description = "Boutique la plus proche d'une position")
@RestController
@RequestMapping("/api")
public class LocalityResource {

    private final PartnerService partnerService;

    public LocalityResource(PartnerService partnerService) {
        this.partnerService = partnerService;
    }

    /**
     * Boutique la plus proche de cette position, dans le rayon de livraison : frais de livraison et distance,
     * jamais l'identité du partenaire. {@code available} faux : aucune boutique à portée.
     */
    @GetMapping("/public/shop/nearest")
    public ShopView nearestShop(
        @RequestParam(value = "latitude", required = false) Double latitude,
        @RequestParam(value = "longitude", required = false) Double longitude
    ) {
        return partnerService.shopView(latitude, longitude);
    }
}
