package com.sansfile.app.service.mapper;

import static com.sansfile.app.domain.ProductImageAsserts.*;
import static com.sansfile.app.domain.ProductImageTestSamples.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductImageMapperTest {

    private ProductImageMapper productImageMapper;

    @BeforeEach
    void setUp() {
        productImageMapper = new ProductImageMapperImpl();
    }

    @Test
    void shouldConvertToDtoAndBack() {
        var expected = getProductImageSample1();
        var actual = productImageMapper.toEntity(productImageMapper.toDto(expected));
        assertProductImageAllPropertiesEquals(expected, actual);
    }
}
