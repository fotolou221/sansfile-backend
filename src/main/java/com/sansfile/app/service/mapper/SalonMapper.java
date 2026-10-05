package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.Salon;
import com.sansfile.app.service.dto.SalonDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link Salon} and its DTO {@link SalonDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SalonMapper extends EntityMapper<SalonDTO, Salon> {
    /** L'agent créateur n'est fixé qu'à la création : une modification ne peut pas le changer. */
    @Override
    @Named("partialUpdate")
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "createdByAgentId", ignore = true)
    void partialUpdate(@MappingTarget Salon entity, SalonDTO dto);
}
