package com.sansfile.app.web.rest.custom;

import com.sansfile.app.service.custom.locality.LocalityService;
import com.sansfile.app.service.custom.locality.LocalityService.AdminLocality;
import com.sansfile.app.service.custom.locality.LocalityService.LocalityForm;
import com.sansfile.app.service.custom.locality.LocalityService.RequestedZone;
import com.sansfile.app.service.custom.locality.PartnerService;
import com.sansfile.app.service.custom.locality.PartnerService.AdminPartner;
import com.sansfile.app.service.custom.locality.PartnerService.OfferForm;
import com.sansfile.app.service.custom.locality.PartnerService.PartnerForm;
import com.sansfile.app.service.custom.locality.PartnerService.PartnerProductRow;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Console d'administration : localités et partenaires boutique (prix de gros, disponibilités).
 * Réservé aux administrateurs (règle générale « /api/** » de la configuration de sécurité).
 */
@Tag(name = "Admin — Localités & partenaires", description = "Localités, partenaires boutique et leurs produits")
@RestController
@RequestMapping("/api/admin")
public class AdminLocalityResource {

    private final LocalityService localityService;
    private final PartnerService partnerService;

    public AdminLocalityResource(LocalityService localityService, PartnerService partnerService) {
        this.localityService = localityService;
        this.partnerService = partnerService;
    }

    public record ActivationRequest(boolean active) {}

    // ── Localités ───────────────────────────────────────────────

    @GetMapping("/localities")
    public List<AdminLocality> localities() {
        return localityService.adminList();
    }

    @PostMapping("/localities")
    public ResponseEntity<AdminLocality> createLocality(@RequestBody LocalityForm form) {
        AdminLocality created = localityService.create(form);
        return ResponseEntity.created(URI.create("/api/admin/localities/" + created.id())).body(created);
    }

    @PutMapping("/localities/{id}")
    public AdminLocality updateLocality(@PathVariable("id") Long id, @RequestBody LocalityForm form) {
        return localityService.update(id, form);
    }

    @PutMapping("/localities/{id}/activation")
    public AdminLocality setLocalityActive(@PathVariable("id") Long id, @RequestBody ActivationRequest request) {
        return localityService.setActive(id, request.active());
    }

    @DeleteMapping("/localities/{id}")
    public ResponseEntity<Void> deleteLocality(@PathVariable("id") Long id) {
        localityService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Zones demandées par les utilisateurs dont la localité n'existe pas encore, des plus demandées aux moins demandées. */
    @GetMapping("/localities/requested")
    public List<RequestedZone> requestedZones() {
        return localityService.requestedZones();
    }

    // ── Partenaires ─────────────────────────────────────────────

    @GetMapping("/partners")
    public List<AdminPartner> partners() {
        return partnerService.list();
    }

    @GetMapping("/partners/{id}")
    public AdminPartner partner(@PathVariable("id") Long id) {
        return partnerService.get(id);
    }

    @PostMapping("/partners")
    public ResponseEntity<AdminPartner> createPartner(@RequestBody PartnerForm form) {
        AdminPartner created = partnerService.create(form);
        return ResponseEntity.created(URI.create("/api/admin/partners/" + created.id())).body(created);
    }

    @PutMapping("/partners/{id}")
    public AdminPartner updatePartner(@PathVariable("id") Long id, @RequestBody PartnerForm form) {
        return partnerService.update(id, form);
    }

    @PutMapping("/partners/{id}/activation")
    public AdminPartner setPartnerActive(@PathVariable("id") Long id, @RequestBody ActivationRequest request) {
        return partnerService.setActive(id, request.active());
    }

    @DeleteMapping("/partners/{id}")
    public ResponseEntity<Void> deletePartner(@PathVariable("id") Long id) {
        partnerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Tout le catalogue, avec l'offre du partenaire (prix de gros, disponibilité) et la marge SansFile. */
    @GetMapping("/partners/{id}/products")
    public List<PartnerProductRow> partnerProducts(@PathVariable("id") Long id) {
        return partnerService.products(id);
    }

    @PutMapping("/partners/{id}/products/{productId}")
    public PartnerProductRow saveOffer(
        @PathVariable("id") Long id,
        @PathVariable("productId") Long productId,
        @RequestBody OfferForm form
    ) {
        return partnerService.saveOffer(id, productId, form);
    }

    @DeleteMapping("/partners/{id}/products/{productId}")
    public ResponseEntity<Void> removeOffer(@PathVariable("id") Long id, @PathVariable("productId") Long productId) {
        partnerService.removeOffer(id, productId);
        return ResponseEntity.noContent().build();
    }
}
