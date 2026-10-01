package com.sansfile.app.web.rest;

import com.sansfile.app.domain.AppNotification;
import com.sansfile.app.repository.AppNotificationRepository;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.security.SecurityUtils;
import com.sansfile.app.service.AppNotificationQueryService;
import com.sansfile.app.service.AppNotificationService;
import com.sansfile.app.service.criteria.AppNotificationCriteria;
import com.sansfile.app.service.dto.AppNotificationDTO;
import com.sansfile.app.service.mapper.AppNotificationMapper;
import com.sansfile.app.web.rest.errors.BadRequestAlertException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
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
 * REST controller for managing {@link com.sansfile.app.domain.AppNotification}.
 */
@Tag(name = "7. Notifications", description = "Gestion des alertes et notifications")
@RestController
@RequestMapping({ "/api/app-notifications", "/api/notifications" })
public class AppNotificationResource {

    private static final Logger LOG = LoggerFactory.getLogger(AppNotificationResource.class);

    private static final String ENTITY_NAME = "appNotification";

    @Value("${jhipster.clientApp.name:sansfileBackend}")
    private String applicationName;

    private final AppNotificationService appNotificationService;

    private final AppNotificationQueryService appNotificationQueryService;

    private final AppNotificationRepository appNotificationRepository;

    private final AppNotificationMapper appNotificationMapper;
    private final com.sansfile.app.service.custom.access.AccessControlService accessControl;

    public AppNotificationResource(
        AppNotificationService appNotificationService,
        AppNotificationQueryService appNotificationQueryService,
        AppNotificationRepository appNotificationRepository,
        AppNotificationMapper appNotificationMapper,
        com.sansfile.app.service.custom.access.AccessControlService accessControl
    ) {
        this.accessControl = accessControl;
        this.appNotificationService = appNotificationService;
        this.appNotificationQueryService = appNotificationQueryService;
        this.appNotificationRepository = appNotificationRepository;
        this.appNotificationMapper = appNotificationMapper;
    }

    /**
     * {@code POST  /app-notifications} : Create a new appNotification.
     *
     * @param appNotificationDTO the appNotificationDTO to create.
     * @return the {@link ResponseEntity} with status {@code 201 (Created)} and with body the new appNotificationDTO, or with status {@code 400 (Bad Request)} if the appNotification has already an ID.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PostMapping("")
    public ResponseEntity<AppNotificationDTO> createAppNotification(@Valid @RequestBody AppNotificationDTO appNotificationDTO)
        throws URISyntaxException {
        LOG.debug("REST request to save AppNotification : {}", appNotificationDTO);
        if (appNotificationDTO.getId() != null) {
            throw new BadRequestAlertException("A new appNotification cannot already have an ID", ENTITY_NAME, "idexists");
        }
        appNotificationDTO = appNotificationService.save(appNotificationDTO);
        return ResponseEntity.created(new URI("/api/app-notifications/" + appNotificationDTO.getId()))
            .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, appNotificationDTO.getId().toString()))
            .body(appNotificationDTO);
    }

    /**
     * {@code PUT  /app-notifications/:id} : Updates an existing appNotification.
     *
     * @param id the id of the appNotificationDTO to save.
     * @param appNotificationDTO the appNotificationDTO to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated appNotificationDTO,
     * or with status {@code 400 (Bad Request)} if the appNotificationDTO is not valid,
     * or with status {@code 500 (Internal Server Error)} if the appNotificationDTO couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PutMapping("/{id}")
    public ResponseEntity<AppNotificationDTO> updateAppNotification(
        @PathVariable(value = "id", required = false) final Long id,
        @Valid @RequestBody AppNotificationDTO appNotificationDTO
    ) throws URISyntaxException {
        LOG.debug("REST request to update AppNotification : {}, {}", id, appNotificationDTO);
        if (appNotificationDTO.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        if (!Objects.equals(id, appNotificationDTO.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!appNotificationService.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        appNotificationDTO = appNotificationService.update(appNotificationDTO);
        return ResponseEntity.ok()
            .headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, appNotificationDTO.getId().toString()))
            .body(appNotificationDTO);
    }

    /**
     * {@code PATCH  /app-notifications/:id} : Partial updates given fields of an existing appNotification, field will ignore if it is null
     *
     * @param id the id of the appNotificationDTO to save.
     * @param appNotificationDTO the appNotificationDTO to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated appNotificationDTO,
     * or with status {@code 400 (Bad Request)} if the appNotificationDTO is not valid,
     * or with status {@code 404 (Not Found)} if the appNotificationDTO is not found,
     * or with status {@code 500 (Internal Server Error)} if the appNotificationDTO couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PatchMapping(value = "/{id}", consumes = { "application/json", "application/merge-patch+json" })
    public ResponseEntity<AppNotificationDTO> partialUpdateAppNotification(
        @PathVariable(value = "id", required = false) final Long id,
        @NotNull @RequestBody AppNotificationDTO appNotificationDTO
    ) throws URISyntaxException {
        LOG.debug("REST request to partial update AppNotification partially : {}, {}", id, appNotificationDTO);
        if (appNotificationDTO.getId() == null) {
            appNotificationDTO.setId(id);
        }
        if (!Objects.equals(id, appNotificationDTO.getId())) {
            throw new BadRequestAlertException("Invalid ID", ENTITY_NAME, "idinvalid");
        }

        if (!appNotificationService.existsById(id)) {
            throw new BadRequestAlertException("Entity not found", ENTITY_NAME, "idnotfound");
        }

        Optional<AppNotificationDTO> result = appNotificationService.partialUpdate(appNotificationDTO);

        return ResponseUtil.wrapOrNotFound(
            result,
            HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, appNotificationDTO.getId().toString())
        );
    }

    /**
     * {@code PATCH /app-notifications/:id/read} : Mark single notification as read.
     */
    @PatchMapping("/{id}/read")
    public ResponseEntity<AppNotificationDTO> markAsRead(@PathVariable("id") Long id) {
        LOG.debug("REST request to mark AppNotification as read : {}", id);
        accessControl.assertCanAccessNotification(id);
        AppNotificationDTO dto = new AppNotificationDTO();
        dto.setId(id);
        dto.setIsRead(true);
        Optional<AppNotificationDTO> result = appNotificationService.partialUpdate(dto);
        return ResponseUtil.wrapOrNotFound(result);
    }

    /**
     * {@code PUT /app-notifications/mark-all-read} : Mark all notifications of current user as read.
     */
    @PutMapping("/mark-all-read")
    public ResponseEntity<Void> markAllAsRead() {
        Optional<String> currentUserLogin = SecurityUtils.getCurrentUserLogin();
        if (currentUserLogin.isPresent()) {
            List<AppNotification> notifs = appNotificationRepository.findByUserLoginOrderByCreatedDateDesc(currentUserLogin.get());
            for (AppNotification n : notifs) {
                if (!Boolean.TRUE.equals(n.getIsRead())) {
                    n.setIsRead(true);
                    appNotificationRepository.save(n);
                }
            }
        }
        return ResponseEntity.ok().build();
    }

    /**
     * {@code GET /app-notifications/my-notifications} : Get notifications for current user.
     */
    @GetMapping("/my-notifications")
    public ResponseEntity<List<AppNotificationDTO>> getMyNotifications() {
        Optional<String> currentUserLogin = SecurityUtils.getCurrentUserLogin();
        if (currentUserLogin.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<AppNotification> notifs = appNotificationRepository.findByUserLoginOrderByCreatedDateDesc(currentUserLogin.get());
        return ResponseEntity.ok(appNotificationMapper.toDto(notifs));
    }

    /**
     * {@code GET  /app-notifications} : get all the App Notifications.
     *
     * @param pageable the pagination information.
     * @param criteria the criteria which the requested entities should match.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of App Notifications in body.
     */
    @GetMapping("")
    public ResponseEntity<List<AppNotificationDTO>> getAllAppNotifications(
        AppNotificationCriteria criteria,
        @org.springdoc.core.annotations.ParameterObject @org.springframework.data.web.SortDefault(
            sort = "createdDate",
            direction = org.springframework.data.domain.Sort.Direction.DESC
        ) Pageable pageable
    ) {
        LOG.debug("REST request to get AppNotifications by criteria: {}", criteria);

        // Hors admin : uniquement les notifications de l'utilisateur connecté
        if (!accessControl.isAdmin()) {
            List<AppNotification> userNotifs = SecurityUtils.getCurrentUserLogin()
                .map(appNotificationRepository::findByUserLoginOrderByCreatedDateDesc)
                .orElse(List.of());
            return ResponseEntity.ok().body(appNotificationMapper.toDto(userNotifs));
        }

        // Admin : filtres et pagination (jamais toutes les notifications de la plateforme d'un coup)
        Page<AppNotificationDTO> page = appNotificationQueryService.findByCriteria(criteria, pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(ServletUriComponentsBuilder.fromCurrentRequest(), page);
        return ResponseEntity.ok().headers(headers).body(page.getContent());
    }

    /**
     * {@code GET  /app-notifications/count} : count all the appNotifications.
     *
     * @param criteria the criteria which the requested entities should match.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the count in body.
     */
    @GetMapping("/count")
    public ResponseEntity<Long> countAppNotifications(AppNotificationCriteria criteria) {
        LOG.debug("REST request to count AppNotifications by criteria: {}", criteria);
        return ResponseEntity.ok().body(appNotificationQueryService.countByCriteria(criteria));
    }

    /**
     * {@code GET  /app-notifications/:id} : get the "id" appNotification.
     *
     * @param id the id of the appNotificationDTO to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the appNotificationDTO, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AppNotificationDTO> getAppNotification(@PathVariable("id") Long id) {
        LOG.debug("REST request to get AppNotification : {}", id);
        accessControl.assertCanAccessNotification(id);
        Optional<AppNotificationDTO> appNotificationDTO = appNotificationService.findOne(id);
        return ResponseUtil.wrapOrNotFound(appNotificationDTO);
    }

    /**
     * {@code DELETE  /app-notifications/:id} : delete the "id" appNotification.
     *
     * @param id the id of the appNotificationDTO to delete.
     * @return the {@link ResponseEntity} with status {@code 204 (NO_CONTENT)}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAppNotification(@PathVariable("id") Long id) {
        LOG.debug("REST request to delete AppNotification : {}", id);
        accessControl.assertCanAccessNotification(id);
        appNotificationService.delete(id);
        return ResponseEntity.noContent()
            .headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, ENTITY_NAME, id.toString()))
            .build();
    }
}
