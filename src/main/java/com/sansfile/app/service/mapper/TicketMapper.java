package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.Salon;
import com.sansfile.app.domain.Ticket;
import com.sansfile.app.domain.User;
import com.sansfile.app.service.dto.SalonDTO;
import com.sansfile.app.service.dto.TicketDTO;
import com.sansfile.app.service.dto.UserDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link Ticket} and its DTO {@link TicketDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TicketMapper extends EntityMapper<TicketDTO, Ticket> {
    @Mapping(target = "user", source = "user", qualifiedByName = "userId")
    @Mapping(target = "salon", source = "salon", qualifiedByName = "salonName")
    @Mapping(target = "ownerAvatarUrl", source = "user.imageUrl")
    TicketDTO toDto(Ticket s);

    @Named("userId")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    @Mapping(target = "imageUrl", source = "imageUrl")
    UserDTO toDtoUserId(User user);

    @Named("salonName")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    SalonDTO toDtoSalonName(Salon salon);
}
