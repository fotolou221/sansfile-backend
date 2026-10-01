package com.sansfile.app.service.mapper;

import static com.sansfile.app.domain.SalonActionAsserts.*;
import static com.sansfile.app.domain.SalonActionTestSamples.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SalonActionMapperTest {

    private SalonActionMapper salonActionMapper;

    @BeforeEach
    void setUp() {
        salonActionMapper = new SalonActionMapperImpl();
    }

    @Test
    void shouldConvertToDtoAndBack() {
        var expected = getSalonActionSample1();
        var actual = salonActionMapper.toEntity(salonActionMapper.toDto(expected));
        assertSalonActionAllPropertiesEquals(expected, actual);
    }
}
