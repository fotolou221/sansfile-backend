package com.sansfile.app.service.mapper;

import static com.sansfile.app.domain.BoutiqueOrderAsserts.*;
import static com.sansfile.app.domain.BoutiqueOrderTestSamples.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoutiqueOrderMapperTest {

    private BoutiqueOrderMapper boutiqueOrderMapper;

    @BeforeEach
    void setUp() {
        boutiqueOrderMapper = new BoutiqueOrderMapperImpl();
    }

    @Test
    void shouldConvertToDtoAndBack() {
        var expected = getBoutiqueOrderSample1();
        var actual = boutiqueOrderMapper.toEntity(boutiqueOrderMapper.toDto(expected));
        assertBoutiqueOrderAllPropertiesEquals(expected, actual);
    }
}
