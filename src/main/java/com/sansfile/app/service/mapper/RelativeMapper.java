package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.Relative;
import com.sansfile.app.domain.User;
import com.sansfile.app.service.dto.RelativeDTO;
import com.sansfile.app.service.dto.UserDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link Relative} and its DTO {@link RelativeDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RelativeMapper extends EntityMapper<RelativeDTO, Relative> {
    @Mapping(target = "user", source = "user", qualifiedByName = "userId")
    RelativeDTO toDto(Relative s);

    @Named("userId")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    UserDTO toDtoUserId(User user);
}
