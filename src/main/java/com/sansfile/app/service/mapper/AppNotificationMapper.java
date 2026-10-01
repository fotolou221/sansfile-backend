package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.AppNotification;
import com.sansfile.app.domain.User;
import com.sansfile.app.service.dto.AppNotificationDTO;
import com.sansfile.app.service.dto.UserDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link AppNotification} and its DTO {@link AppNotificationDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AppNotificationMapper extends EntityMapper<AppNotificationDTO, AppNotification> {
    @Mapping(target = "user", source = "user", qualifiedByName = "userId")
    AppNotificationDTO toDto(AppNotification s);

    @Named("userId")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    UserDTO toDtoUserId(User user);
}
