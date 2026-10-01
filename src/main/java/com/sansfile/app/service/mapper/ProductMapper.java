package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.Product;
import com.sansfile.app.domain.ProductCategory;
import com.sansfile.app.service.dto.ProductCategoryDTO;
import com.sansfile.app.service.dto.ProductDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link Product} and its DTO {@link ProductDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProductMapper extends EntityMapper<ProductDTO, Product> {
    @Mapping(target = "category", source = "category", qualifiedByName = "productCategoryName")
    ProductDTO toDto(Product s);

    @Named("productCategoryName")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "slug", source = "slug")
    ProductCategoryDTO toDtoProductCategoryName(ProductCategory productCategory);
}
