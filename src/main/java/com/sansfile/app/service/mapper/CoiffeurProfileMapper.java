package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.CoiffeurProfile;
import com.sansfile.app.domain.Salon;
import com.sansfile.app.domain.User;
import com.sansfile.app.service.dto.CoiffeurProfileDTO;
import com.sansfile.app.service.dto.SalonDTO;
import com.sansfile.app.service.dto.UserDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link CoiffeurProfile} and its DTO {@link CoiffeurProfileDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CoiffeurProfileMapper extends EntityMapper<CoiffeurProfileDTO, CoiffeurProfile> {
    @Mapping(target = "user", source = "user", qualifiedByName = "userId")
    @Mapping(target = "salon", source = "salon", qualifiedByName = "salonName")
    CoiffeurProfileDTO toDto(CoiffeurProfile s);

    @Named("userId")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    UserDTO toDtoUserId(User user);

    @Named("salonName")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    SalonDTO toDtoSalonName(Salon salon);
}
