package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.ProductCategory;
import com.sansfile.app.service.dto.ProductCategoryDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link ProductCategory} and its DTO {@link ProductCategoryDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProductCategoryMapper extends EntityMapper<ProductCategoryDTO, ProductCategory> {}
