package com.sansfile.app.service.impl;

import com.sansfile.app.domain.PlatformSettings;
import com.sansfile.app.repository.PlatformSettingsRepository;
import com.sansfile.app.service.PlatformSettingsService;
import com.sansfile.app.service.dto.PlatformSettingsDTO;
import com.sansfile.app.service.mapper.PlatformSettingsMapper;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service Implementation for managing {@link com.sansfile.app.domain.PlatformSettings}.
 */
@Service
@Transactional
public class PlatformSettingsServiceImpl implements PlatformSettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformSettingsServiceImpl.class);

    private final PlatformSettingsRepository platformSettingsRepository;
    private final PlatformSettingsMapper platformSettingsMapper;
    private final com.sansfile.app.service.custom.realtime.RealtimeEventService realtimeEventService;
    private final com.sansfile.app.service.custom.maintenance.MaintenanceModeService maintenanceModeService;

    public PlatformSettingsServiceImpl(
        PlatformSettingsRepository platformSettingsRepository,
        PlatformSettingsMapper platformSettingsMapper,
        com.sansfile.app.service.custom.realtime.RealtimeEventService realtimeEventService,
        com.sansfile.app.service.custom.maintenance.MaintenanceModeService maintenanceModeService
    ) {
        this.platformSettingsRepository = platformSettingsRepository;
        this.platformSettingsMapper = platformSettingsMapper;
        this.realtimeEventService = realtimeEventService;
        this.maintenanceModeService = maintenanceModeService;
    }

    /** Coordonnées et mode maintenance : les pages ouvertes se mettent à jour sans rechargement. */
    private PlatformSettingsDTO broadcastUpdate(PlatformSettingsDTO dto) {
        maintenanceModeService.evict();
        realtimeEventService.broadcast("SETTINGS_UPDATED", dto);
        return dto;
    }

    @Override
    public PlatformSettingsDTO save(PlatformSettingsDTO platformSettingsDTO) {
        LOG.debug("Request to save PlatformSettings : {}", platformSettingsDTO);
        PlatformSettings platformSettings = platformSettingsMapper.toEntity(platformSettingsDTO);
        platformSettings = platformSettingsRepository.save(platformSettings);
        return broadcastUpdate(platformSettingsMapper.toDto(platformSettings));
    }

    @Override
    public PlatformSettingsDTO update(PlatformSettingsDTO platformSettingsDTO) {
        LOG.debug("Request to update PlatformSettings : {}", platformSettingsDTO);
        PlatformSettings platformSettings = platformSettingsMapper.toEntity(platformSettingsDTO);
        platformSettings = platformSettingsRepository.save(platformSettings);
        return broadcastUpdate(platformSettingsMapper.toDto(platformSettings));
    }

    @Override
    public Optional<PlatformSettingsDTO> partialUpdate(PlatformSettingsDTO platformSettingsDTO) {
        LOG.debug("Request to partially update PlatformSettings : {}", platformSettingsDTO);

        return platformSettingsRepository
            .findById(platformSettingsDTO.getId())
            .map(existingPlatformSettings -> {
                platformSettingsMapper.partialUpdate(existingPlatformSettings, platformSettingsDTO);
                return existingPlatformSettings;
            })
            .map(platformSettingsRepository::save)
            .map(platformSettingsMapper::toDto)
            .map(this::broadcastUpdate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlatformSettingsDTO> findAll() {
        LOG.debug("Request to get all PlatformSettingses");
        return platformSettingsRepository
            .findAll()
            .stream()
            .map(platformSettingsMapper::toDto)
            .collect(Collectors.toCollection(LinkedList::new));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlatformSettingsDTO> findOne(Long id) {
        LOG.debug("Request to get PlatformSettings : {}", id);
        return platformSettingsRepository.findById(id).map(platformSettingsMapper::toDto);
    }

    @Override
    public void delete(Long id) {
        LOG.debug("Request to delete PlatformSettings : {}", id);
        platformSettingsRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return platformSettingsRepository.existsById(id);
    }
}
