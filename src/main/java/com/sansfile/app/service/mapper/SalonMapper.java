package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.Salon;
import com.sansfile.app.service.dto.SalonDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link Salon} and its DTO {@link SalonDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SalonMapper extends EntityMapper<SalonDTO, Salon> {}
