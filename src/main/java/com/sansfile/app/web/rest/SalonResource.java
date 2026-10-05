package com.sansfile.app.web.rest;

import com.sansfile.app.service.SalonQueryService;
import com.sansfile.app.service.SalonService;
import com.sansfile.app.service.criteria.SalonCriteria;
import com.sansfile.app.service.dto.SalonDTO;
import com.sansfile.app.web.rest.errors.BadRequestAlertException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tech.jhipster.web.util.HeaderUtil;
import tech.jhipster.web.util.PaginationUtil;
import tech.jhipster.web.util.ResponseUtil;

/**
 * REST controller for managing {@link com.sansfile.app.domain.Salon}.
 */
@Tag(name = "2. Salons & Barbiers", description = "Recherche et gestion des salons de coiffure")
@RestController
@RequestMapping("/api/salons")
public class SalonResource {

    private static final Logger LOG = LoggerFactory.getLogger(SalonResource.class);

    private static final String ENTITY_NAME = "salon";

    @Value("${jhipster.clientApp.name:sansfileBackend}")
    private String applicationName;

    private final SalonService salonService;

    private final SalonQueryService salonQueryService;

    private final com.sansfile.app.service.custom.salon.SalonCustomService salonCustomService;

    private final com.sansfile.app.service.custom.realtime.RealtimeEventService realtimeEventService;
    private final com.sansfile.app.repository.UserRepository userRepository;
    private final com.sansfile.app.repository.AuthorityRepository authorityRepository;
    private final com.sansfile.app.repository.CoiffeurProfileRepository coiffeurProfileRepository;
    private final com.sansfile.app.repository.SalonRepository salonRepository;
    private final com.sansfile.app.repository.TicketRepository ticketRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final com.sansfile.app.service.mapper.SalonMapper salonMapper;
    private final com.sansfile.app.service.custom.access.AccessControlService accessControl;
    private final com.sansfile.app.service.custom.agent.AgentAccountService agentAccountService;
    private final com.sansfile.app.service.custom.agent.AgentActivityService agentActivityService;

    public SalonResource(
        SalonService salonService,
        SalonQueryService salonQueryService,
        com.sansfile.app.service.custom.salon.SalonCustomService salonCustomService,
        com.sansfile.app.service.custom.realtime.RealtimeEventService realtimeEventService,
        com.sansfile.app.repository.UserRepository userRepository,
        com.sansfile.app.repository.AuthorityRepository authorityRepository,
        com.sansfile.app.repository.CoiffeurProfileRepository coiffeurProfileRepository,
        com.sansfile.app.repository.SalonRepository salonRepository,
        com.sansfile.app.repository.TicketRepository ticketRepository,
        org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
        com.sansfile.app.service.mapper.SalonMapper salonMapper,
        com.sansfile.app.service.custom.access.AccessControlService accessControl,
        com.sansfile.app.service.custom.agent.AgentAccountService agentAccountService,
        com.sansfile.app.service.custom.agent.AgentActivityService agentActivityService
    ) {
        this.accessControl = accessControl;
        this.agentAccountService = agentAccountService;
        this.agentActivityService = agentActivityService;
        this.salonService = salonService;
        this.salonQueryService = salonQueryService;
        this.salonCustomService = salonCustomService;
        this.realtimeEventService = realtimeEventService;
        this.userRepository = userRepository;
        this.authorityRepository = authorityRepository;
        this.coiffeurProfileRepository = coiffeurProfileRepository;
        this.salonRepository = salonRepository;
        this.ticketRepository = ticketRepository;
        this.passwordEncoder = passwordEncoder;
        this.salonMapper = salonMapper;
    }

    /**
     * {@code POST  /salons} : Create a new salon.
     *
     * @param salonDTO the salonDTO to create.
     * @return the {@link ResponseEntity} with status {@code 201 (Created)} and with body the new salonDTO, or with status {@code 400 (Bad Request)} if the salon has already an ID.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PostMapping("")
    public ResponseEntity<SalonDTO> createSalon(@Valid @RequestBody SalonDTO salonDTO) throws URISyntaxException {
        LOG.debug("REST request to save Salon : {}", salonDTO);
        if (salonDTO.getId() != null) {
            throw new BadRequestAlertException("A new salon cannot already have an ID", ENTITY_NAME, "idexists");
        }

        // Agent de terrain : salon rattaché à son compte, réglages d'exploitation imposés
        Long agentId = null;
        if (com.sansfile.app.service.custom.agent.AgentAccountService.isAgentOnly()) {
            agentId = agentAccountService.requireActiveAgent().getId();
            prepareAgentSalon(salonDTO, agentId);
        } else {
            salonDTO.setCreatedByAgentId(null);
        }

        String normalizedPhone = normalizePhoneForAccount(salonDTO.getPhone());
        if (!normalizedPhone.isBlank()) {
            if (phoneAlreadyUsed(normalizedPhone)) {
                throw new BadRequestAlertException(
                    "Ce numéro de téléphone est déjà associé à un autre salon.",
                    ENTITY_NAME,
                    "phonealreadyused"
                );
            }
            salonDTO.setPhone(normalizedPhone);
        }

        final String ownerDisplayName = normalizeOwnerName(salonDTO.getOwnerName(), salonDTO.getCoiffeurName());
        if (!ownerDisplayName.isBlank()) {
            salonDTO.setOwnerName(ownerDisplayName);
            salonDTO.setCoiffeurName(ownerDisplayName);
        }

        salonDTO = salonService.save(salonDTO);

        // Auto-provision ou association du compte Coiffeur Propriétaire avec ROLE_COIFFEUR
        if (salonDTO.getPhone() != null && !salonDTO.getPhone().isBlank()) {
            final String cleanPhone = normalizePhoneForAccount(salonDTO.getPhone());
            final String salonName = salonDTO.getName();
            com.sansfile.app.domain.Salon salonEntity = salonRepository.findById(salonDTO.getId()).orElse(null);

            if (salonEntity != null) {
                com.sansfile.app.domain.User ownerUser = userRepository.findOneWithAuthoritiesByLogin(cleanPhone).orElseGet(() -> {
                    com.sansfile.app.domain.User u = new com.sansfile.app.domain.User();
                    u.setLogin(cleanPhone);
                    // Connexion par SMS uniquement : mot de passe aléatoire, jamais communiqué
                    u.setPassword(passwordEncoder.encode(com.sansfile.app.service.custom.access.RandomPasswords.generate()));
                    applyUserDisplayName(u, ownerDisplayName, "Coiffeur Proprietaire");
                    u.setEmail(cleanPhone.replace("+", "") + "@sansfile.sn");
                    u.setActivated(true);
                    u.setLangKey("fr");
                    return u;
                });

                java.util.Set<com.sansfile.app.domain.Authority> auths = new java.util.HashSet<>(ownerUser.getAuthorities());
                authorityRepository.findById(com.sansfile.app.security.AuthoritiesConstants.COIFFEUR).ifPresent(auths::add);
                authorityRepository.findById(com.sansfile.app.security.AuthoritiesConstants.USER).ifPresent(auths::add);
                auths.removeIf(a -> com.sansfile.app.security.AuthoritiesConstants.CLIENT.equals(a.getName()));
                ownerUser.setAuthorities(auths);
                if (!ownerDisplayName.isBlank()) {
                    applyUserDisplayName(ownerUser, ownerDisplayName, userDisplayName(ownerUser));
                }
                ownerUser = userRepository.save(ownerUser);

                final com.sansfile.app.domain.User finalOwner = ownerUser;
                com.sansfile.app.domain.CoiffeurProfile profile = coiffeurProfileRepository.findByPhone(cleanPhone).orElseGet(() -> {
                    com.sansfile.app.domain.CoiffeurProfile cp = new com.sansfile.app.domain.CoiffeurProfile();
                    cp.setPhone(cleanPhone);
                    cp.setCreatedDate(java.time.Instant.now());
                    return cp;
                });
                profile.setName(!ownerDisplayName.isBlank() ? ownerDisplayName : fallbackOwnerName(finalOwner, salonName));
                profile.setUser(finalOwner);
                profile.setSalon(salonEntity);
                profile.setActive(true);
                coiffeurProfileRepository.save(profile);
            }
        }

        salonDTO = enrichOwnerInfo(salonDTO);
        if (agentId != null) {
            agentActivityService.record(
                agentId,
                com.sansfile.app.domain.enumeration.AgentAction.SALON_CREATED,
                "Salon « " + salonDTO.getName() + " » inscrit (" + salonDTO.getDistrict() + ", tél. " + salonDTO.getPhone() + ")",
                salonDTO.getId()
            );
        }
        realtimeEventService.broadcast("SALON_CREATED", salonDTO);
        return ResponseEntity.created(new URI("/api/salons/" + salonDTO.getId()))
            .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, salonDTO.getId().toString()))
            .body(salonDTO);
    }

    /**
     * {@code PUT  /salons/:id} : Updates an existing salon.
     *
     * @param id the id of the salonDTO to save.
     * @param salonDTO the salonDTO to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated salonDTO,
     * or with status {@code 400 (Bad Request)} if the salonDTO is not valid,
     * or with status {@code 500 (Internal Server Error)} if the salonDTO couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PutMapping("/{id}")
    public ResponseEntity<SalonDTO> updateSalon(
        @PathVariable(value = "id", required = false) final Long id,
        @Valid @RequestBody SalonDTO salonDTO
    ) throws URISyntaxException {
        LOG.debug("REST request to update Salon : {}, {}", id, salonDTO);
        if (salonDTO.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, salonDTO.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!salonService.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        salonDTO = salonService.update(salonDTO);
        // L'agent créateur n'est jamais modifié (colonne non modifiable) : renvoyer la valeur enregistrée
        salonDTO.setCreatedByAgentId(salonRepository.findById(id).map(com.sansfile.app.domain.Salon::getCreatedByAgentId).orElse(null));
        syncOwnerNameFromDto(salonDTO);
        salonDTO = enrichOwnerInfo(salonDTO);
        realtimeEventService.broadcast("SALON_UPDATED", salonDTO);
        return ResponseEntity.ok()
            .headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, salonDTO.getId().toString()))
            .body(salonDTO);
    }

    /**
     * {@code PATCH  /salons/:id} : Partial updates given fields of an existing salon, field will ignore if it is null
     *
     * @param id the id of the salonDTO to save.
     * @param salonDTO the salonDTO to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated salonDTO,
     * or with status {@code 400 (Bad Request)} if the salonDTO is not valid,
     * or with status {@code 404 (Not Found)} if the salonDTO is not found,
     * or with status {@code 500 (Internal Server Error)} if the salonDTO couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PatchMapping(value = "/{id}", consumes = { "application/json", "application/merge-patch+json" })
    public ResponseEntity<SalonDTO> partialUpdateSalon(
        @PathVariable(value = "id", required = false) final Long id,
        @NotNull @RequestBody SalonDTO salonDTO
    ) throws URISyntaxException {
        LOG.debug("REST request to partial update Salon partially : {}, {}", id, salonDTO);
        if (salonDTO.getId() == null) {
            salonDTO.setId(id);
        }
        if (!Objects.equals(id, salonDTO.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!salonService.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        Long agentId = null;
        if (com.sansfile.app.service.custom.agent.AgentAccountService.isAgentOnly()) {
            // Un agent ne modifie que les salons qu'il a lui-même inscrits
            agentId = agentAccountService.requireActiveAgent().getId();
            if (!salonRepository.existsByIdAndCreatedByAgentId(id, agentId)) {
                throw new org.springframework.security.access.AccessDeniedException(
                    "Vous ne pouvez modifier que les salons que vous avez inscrits."
                );
            }
            restrictToAgentFields(salonDTO);
        } else {
            accessControl.assertCanManageSalon(id);
            if (!accessControl.isAdmin()) {
                restrictToPresentationFields(salonDTO);
            }
        }

        Optional<SalonDTO> result = salonService.partialUpdate(salonDTO).map(dto -> {
            syncOwnerNameFromDto(salonDTO);
            return enrichOwnerInfo(dto);
        });
        result.ifPresent(dto -> realtimeEventService.broadcast("SALON_UPDATED", dto));
        if (agentId != null && result.isPresent()) {
            agentActivityService.record(
                agentId,
                com.sansfile.app.domain.enumeration.AgentAction.SALON_UPDATED,
                "Salon « " + result.get().getName() + " » modifié : " + agentChangedFields(salonDTO),
                id
            );
        }

        return ResponseUtil.wrapOrNotFound(
            result,
            HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, salonDTO.getId().toString())
        );
    }

    /**
     * Salon inscrit par un agent de terrain : rattaché à son compte, fermé jusqu'à ce que le coiffeur
     * l'ouvre depuis son espace, file d'attente vide.
     */
    private static void prepareAgentSalon(SalonDTO dto, Long agentId) {
        dto.setCreatedByAgentId(agentId);
        dto.setStatus(com.sansfile.app.domain.enumeration.SalonStatus.CLOSED);
        dto.setActive(true);
        dto.setPeopleWaiting(0);
        dto.setEstimatedWaitMinutes(0);
        dto.setCreatedDate(null);
        dto.setLastModifiedDate(null);
    }

    /**
     * Un agent corrige la fiche des salons qu'il a inscrits (identité, adresse, position, photos) :
     * téléphone (compte du coiffeur), propriétaire, statut et file d'attente restent gérés ailleurs.
     */
    private static void restrictToAgentFields(SalonDTO dto) {
        dto.setSlug(null);
        dto.setStatus(null);
        dto.setPhone(null);
        dto.setEstimatedWaitMinutes(null);
        dto.setPeopleWaiting(null);
        dto.setOwnerName(null);
        dto.setCoiffeurName(null);
        dto.setActive(null);
        dto.setCreatedDate(null);
        dto.setLastModifiedDate(null);
        dto.setCreatedByAgentId(null);
    }

    /** Champs envoyés par l'agent, pour le journal (« nom, adresse, photos »). */
    private static String agentChangedFields(SalonDTO dto) {
        java.util.List<String> fields = new java.util.ArrayList<>();
        if (dto.getName() != null) fields.add("nom");
        if (dto.getDistrict() != null) fields.add("quartier");
        if (dto.getLocation() != null) fields.add("ville");
        if (dto.getAddress() != null) fields.add("adresse");
        if (dto.getOpeningHours() != null) fields.add("horaires");
        if (dto.getLatitude() != null || dto.getLongitude() != null) fields.add("position GPS");
        if (dto.getAvatarUrl() != null || dto.getCoverUrl() != null) fields.add("photos");
        return fields.isEmpty() ? "aucun champ" : String.join(", ", fields);
    }

    /**
     * Un coiffeur ne modifie que la présentation de son salon (photos, horaires) :
     * identité, téléphone (identifiant du compte), statut et position restent gérés par l'admin.
     */
    private static void restrictToPresentationFields(SalonDTO dto) {
        dto.setName(null);
        dto.setSlug(null);
        dto.setLocation(null);
        dto.setDistrict(null);
        dto.setAddress(null);
        dto.setStatus(null);
        dto.setPhone(null);
        dto.setEstimatedWaitMinutes(null);
        dto.setPeopleWaiting(null);
        dto.setOwnerName(null);
        dto.setCoiffeurName(null);
        dto.setLatitude(null);
        dto.setLongitude(null);
        dto.setActive(null);
        dto.setCreatedDate(null);
        dto.setLastModifiedDate(null);
    }

    /**
     * {@code PUT /salons/:id/toggle-status} : Bascule l'état d'ouverture/fermeture du salon.
     */
    @PutMapping("/{id}/toggle-status")
    public ResponseEntity<SalonDTO> toggleSalonStatus(@PathVariable("id") String idOrSlug) {
        LOG.debug("REST request to toggle Salon status : {}", idOrSlug);

        Optional<com.sansfile.app.domain.Salon> salonOpt = Optional.empty();
        try {
            Long id = Long.parseLong(idOrSlug);
            salonOpt = salonRepository.findById(id);
        } catch (NumberFormatException ignored) {
            // Non-numeric value, fallback to slug lookup.
        }

        if (salonOpt.isEmpty()) {
            salonOpt = salonRepository.findOneBySlug(idOrSlug);
        }

        com.sansfile.app.domain.Salon salon = salonOpt.orElseThrow(() ->
            new BadRequestAlertException("Salon not found", ENTITY_NAME, "idnotfound")
        );
        accessControl.assertCanManageSalon(salon.getId());

        com.sansfile.app.domain.enumeration.SalonStatus nextStatus =
            salon.getStatus() == com.sansfile.app.domain.enumeration.SalonStatus.OPEN
                ? com.sansfile.app.domain.enumeration.SalonStatus.CLOSED
                : com.sansfile.app.domain.enumeration.SalonStatus.OPEN;

        salon.setStatus(nextStatus);
        com.sansfile.app.domain.Salon saved = salonRepository.save(salon);
        SalonDTO dto = enrichOwnerInfo(salonMapper.toDto(saved));
        realtimeEventService.broadcast("SALON_UPDATED", dto);
        return ResponseEntity.ok(dto);
    }

    /**
     * {@code GET  /salons} : get all the Salons.
     *
     * @param pageable the pagination information.
     * @param criteria the criteria which the requested entities should match.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of Salons in body.
     */
    @GetMapping("")
    public ResponseEntity<List<SalonDTO>> getAllSalons(
        SalonCriteria criteria,
        @org.springdoc.core.annotations.ParameterObject Pageable pageable
    ) {
        LOG.debug("REST request to get Salons by criteria: {}", criteria);

        Page<SalonDTO> page = salonQueryService.findByCriteria(criteria, pageable);
        page.getContent().forEach(dto -> {
            long liveWaiting = ticketRepository.countBySalonIdAndStatusIn(
                dto.getId(),
                List.of(
                    com.sansfile.app.domain.enumeration.TicketStatus.WAITING,
                    com.sansfile.app.domain.enumeration.TicketStatus.YOUR_TURN
                )
            );
            dto.setPeopleWaiting((int) liveWaiting);
            dto.setEstimatedWaitMinutes((int) liveWaiting * 20);
            enrichOwnerInfo(dto);
        });
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(ServletUriComponentsBuilder.fromCurrentRequest(), page);
        return ResponseEntity.ok().headers(headers).body(page.getContent());
    }

    /**
     * {@code GET  /salons/count} : count all the salons.
     *
     * @param criteria the criteria which the requested entities should match.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the count in body.
     */
    @GetMapping("/count")
    public ResponseEntity<Long> countSalons(SalonCriteria criteria) {
        LOG.debug("REST request to count Salons by criteria: {}", criteria);
        return ResponseEntity.ok().body(salonQueryService.countByCriteria(criteria));
    }

    /**
     * {@code GET  /salons/:idOrSlug} : get the "id" or "slug" salon.
     *
     * @param idOrSlug the id or slug of the salonDTO to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the salonDTO, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/{idOrSlug}")
    public ResponseEntity<SalonDTO> getSalon(@PathVariable("idOrSlug") String idOrSlug) {
        LOG.debug("REST request to get Salon by id or slug : {}", idOrSlug);
        Optional<SalonDTO> result = Optional.empty();
        try {
            Long id = Long.parseLong(idOrSlug);
            result = salonService.findOne(id);
        } catch (NumberFormatException ignored) {
            // Non-numeric ID, lookup by slug
        }
        if (result.isEmpty()) {
            result = salonCustomService.findBySlug(idOrSlug);
        }

        result.ifPresent(dto -> {
            long liveWaiting = ticketRepository.countBySalonIdAndStatusIn(
                dto.getId(),
                List.of(
                    com.sansfile.app.domain.enumeration.TicketStatus.WAITING,
                    com.sansfile.app.domain.enumeration.TicketStatus.YOUR_TURN
                )
            );
            dto.setPeopleWaiting((int) liveWaiting);
            dto.setEstimatedWaitMinutes((int) liveWaiting * 20);
            enrichOwnerInfo(dto);
        });

        return ResponseUtil.wrapOrNotFound(result);
    }

    /**
     * {@code DELETE  /salons/:id} : delete the "id" salon.
     *
     * @param id the id of the salonDTO to delete.
     * @return the {@link ResponseEntity} with status {@code 204 (NO_CONTENT)}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSalon(@PathVariable("id") Long id) {
        LOG.debug("REST request to delete Salon : {}", id);
        salonService.delete(id);
        realtimeEventService.broadcast("SALON_DELETED", java.util.Map.of("id", id));
        return ResponseEntity.noContent()
            .headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, ENTITY_NAME, id.toString()))
            .build();
    }

    private SalonDTO enrichOwnerInfo(SalonDTO salonDTO) {
        if (salonDTO == null || salonDTO.getId() == null) {
            return salonDTO;
        }

        coiffeurProfileRepository
            .findBySalonId(salonDTO.getId())
            .stream()
            .findFirst()
            .ifPresent(profile -> {
                String ownerName = resolveOwnerDisplayName(profile, salonDTO.getName());
                if (!ownerName.isBlank()) {
                    salonDTO.setOwnerName(ownerName);
                    salonDTO.setCoiffeurName(ownerName);
                }
            });

        return salonDTO;
    }

    private void syncOwnerNameFromDto(SalonDTO salonDTO) {
        if (salonDTO == null || salonDTO.getId() == null) {
            return;
        }

        String ownerName = normalizeOwnerName(salonDTO.getOwnerName(), salonDTO.getCoiffeurName());
        if (ownerName.isBlank()) {
            return;
        }

        coiffeurProfileRepository.findBySalonId(salonDTO.getId()).forEach(profile -> {
            profile.setName(ownerName);
            if (profile.getUser() != null) {
                applyUserDisplayName(profile.getUser(), ownerName, userDisplayName(profile.getUser()));
                userRepository.save(profile.getUser());
            }
            coiffeurProfileRepository.save(profile);
        });
    }

    private String resolveOwnerDisplayName(com.sansfile.app.domain.CoiffeurProfile profile, String salonName) {
        if (profile == null) {
            return "";
        }

        String userName = normalizeOwnerName(userDisplayName(profile.getUser()));
        if (!userName.isBlank() && isRealOwnerName(userName)) {
            return userName;
        }

        String profileName = normalizeOwnerName(cleanGeneratedOwnerName(profile.getName(), salonName));
        if (!profileName.isBlank() && isRealOwnerName(profileName)) {
            return profileName;
        }

        if (!userName.isBlank()) {
            return userName;
        }

        if (profile.getName() != null && !profile.getName().isBlank()) {
            return cleanGeneratedOwnerName(profile.getName(), null);
        }

        return "";
    }

    private String fallbackOwnerName(com.sansfile.app.domain.User user, String salonName) {
        String userName = userDisplayName(user);
        if (isRealOwnerName(userName)) {
            return userName;
        }
        return "Coiffeur Proprietaire";
    }

    private String userDisplayName(com.sansfile.app.domain.User user) {
        if (user == null) {
            return "";
        }

        String firstName = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String lastName = user.getLastName() != null ? user.getLastName().trim() : "";
        return (firstName + (lastName.isBlank() ? "" : " " + lastName)).trim();
    }

    private void applyUserDisplayName(com.sansfile.app.domain.User user, String displayName, String fallbackName) {
        if (user == null) {
            return;
        }

        String cleanName = normalizeOwnerName(displayName);
        if (cleanName.isBlank()) {
            cleanName = normalizeOwnerName(fallbackName);
        }
        if (cleanName.isBlank()) {
            cleanName = "Coiffeur Proprietaire";
        }

        String[] parts = cleanName.split("\\s+", 2);
        user.setFirstName(parts[0]);
        user.setLastName(parts.length > 1 ? parts[1] : "");
    }

    private String cleanGeneratedOwnerName(String ownerName, String salonName) {
        String cleanName = ownerName != null ? ownerName.trim() : "";
        if (cleanName.isBlank()) {
            return "";
        }

        return cleanName.replace("(Propriétaire)", "").replace("(Proprietaire)", "").trim();
    }

    private boolean isRealOwnerName(String ownerName) {
        String cleanName = normalizeOwnerName(ownerName);
        if (cleanName.isBlank()) {
            return false;
        }

        String normalized = cleanName.toLowerCase();
        return !normalized.equals("coiffeur proprietaire") && !normalized.equals("barbier sansfile");
    }

    private String normalizeOwnerName(String... names) {
        if (names == null) {
            return "";
        }

        for (String name : names) {
            if (name != null && !name.trim().isBlank()) {
                return name.trim().replaceAll("\\s+", " ");
            }
        }
        return "";
    }

    private boolean phoneAlreadyUsed(String normalizedPhone) {
        return (
            salonRepository
                .findAll()
                .stream()
                .anyMatch(salon -> samePhone(normalizedPhone, salon.getPhone())) ||
            coiffeurProfileRepository
                .findAll()
                .stream()
                .anyMatch(profile -> profile.getSalon() != null && samePhone(normalizedPhone, profile.getPhone()))
        );
    }

    private boolean samePhone(String normalizedPhone, String existingPhone) {
        return normalizedPhone.equals(normalizePhoneForAccount(existingPhone));
    }

    private String normalizePhoneForAccount(String rawPhone) {
        if (rawPhone == null) {
            return "";
        }

        String trimmed = rawPhone.trim();
        if (trimmed.isBlank()) {
            return "";
        }

        String compact = trimmed.replaceAll("[^0-9+]", "");
        String digits = compact.replaceAll("[^0-9]", "");
        if (digits.length() < 9) {
            return trimmed.replaceAll("\\s+", "");
        }

        if (digits.length() == 10 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }

        if (compact.startsWith("00")) {
            return "+" + compact.substring(2);
        }
        if (compact.startsWith("+")) {
            return "+" + digits;
        }
        if (digits.startsWith("221")) {
            return "+" + digits;
        }
        if (digits.length() == 9) {
            return "+221" + digits;
        }
        return "+" + digits;
    }
}
