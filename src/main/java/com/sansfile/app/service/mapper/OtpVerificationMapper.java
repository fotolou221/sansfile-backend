package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.OtpVerification;
import com.sansfile.app.service.dto.OtpVerificationDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link OtpVerification} and its DTO {@link OtpVerificationDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface OtpVerificationMapper extends EntityMapper<OtpVerificationDTO, OtpVerification> {}
