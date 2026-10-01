package com.sansfile.app.service.mapper;

import static com.sansfile.app.domain.OtpVerificationAsserts.*;
import static com.sansfile.app.domain.OtpVerificationTestSamples.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtpVerificationMapperTest {

    private OtpVerificationMapper otpVerificationMapper;

    @BeforeEach
    void setUp() {
        otpVerificationMapper = new OtpVerificationMapperImpl();
    }

    @Test
    void shouldConvertToDtoAndBack() {
        var expected = getOtpVerificationSample1();
        var actual = otpVerificationMapper.toEntity(otpVerificationMapper.toDto(expected));
        assertOtpVerificationAllPropertiesEquals(expected, actual);
    }
}
