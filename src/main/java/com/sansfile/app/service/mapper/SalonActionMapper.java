package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.Salon;
import com.sansfile.app.domain.SalonAction;
import com.sansfile.app.service.dto.SalonActionDTO;
import com.sansfile.app.service.dto.SalonDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link SalonAction} and its DTO {@link SalonActionDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SalonActionMapper extends EntityMapper<SalonActionDTO, SalonAction> {
    @Mapping(target = "salon", source = "salon", qualifiedByName = "salonName")
    SalonActionDTO toDto(SalonAction s);

    @Named("salonName")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    SalonDTO toDtoSalonName(Salon salon);
}
