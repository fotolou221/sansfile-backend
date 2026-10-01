package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.PlatformSettings;
import com.sansfile.app.service.dto.PlatformSettingsDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link PlatformSettings} and its DTO {@link PlatformSettingsDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PlatformSettingsMapper extends EntityMapper<PlatformSettingsDTO, PlatformSettings> {}
