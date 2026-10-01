package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.FavoriteSalon;
import com.sansfile.app.domain.Salon;
import com.sansfile.app.domain.User;
import com.sansfile.app.service.dto.FavoriteSalonDTO;
import com.sansfile.app.service.dto.SalonDTO;
import com.sansfile.app.service.dto.UserDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link FavoriteSalon} and its DTO {@link FavoriteSalonDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FavoriteSalonMapper extends EntityMapper<FavoriteSalonDTO, FavoriteSalon> {
    @Mapping(target = "user", source = "user", qualifiedByName = "userId")
    @Mapping(target = "salon", source = "salon", qualifiedByName = "salonId")
    FavoriteSalonDTO toDto(FavoriteSalon s);

    @Named("userId")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    UserDTO toDtoUserId(User user);

    @Named("salonId")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    SalonDTO toDtoSalonId(Salon salon);
}
