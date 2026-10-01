package com.sansfile.app.service.mapper;

import static com.sansfile.app.domain.FavoriteSalonAsserts.*;
import static com.sansfile.app.domain.FavoriteSalonTestSamples.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FavoriteSalonMapperTest {

    private FavoriteSalonMapper favoriteSalonMapper;

    @BeforeEach
    void setUp() {
        favoriteSalonMapper = new FavoriteSalonMapperImpl();
    }

    @Test
    void shouldConvertToDtoAndBack() {
        var expected = getFavoriteSalonSample1();
        var actual = favoriteSalonMapper.toEntity(favoriteSalonMapper.toDto(expected));
        assertFavoriteSalonAllPropertiesEquals(expected, actual);
    }
}
