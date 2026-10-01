package com.sansfile.app.service.mapper;

import com.sansfile.app.domain.BoutiqueOrder;
import com.sansfile.app.domain.OrderItem;
import com.sansfile.app.domain.Product;
import com.sansfile.app.service.dto.BoutiqueOrderDTO;
import com.sansfile.app.service.dto.OrderItemDTO;
import com.sansfile.app.service.dto.ProductDTO;
import org.mapstruct.*;

/**
 * Mapper for the entity {@link OrderItem} and its DTO {@link OrderItemDTO}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface OrderItemMapper extends EntityMapper<OrderItemDTO, OrderItem> {
    @Mapping(target = "product", source = "product", qualifiedByName = "productId")
    @Mapping(target = "order", source = "order", qualifiedByName = "boutiqueOrderOrderNumber")
    OrderItemDTO toDto(OrderItem s);

    @Named("productId")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    ProductDTO toDtoProductId(Product product);

    @Named("boutiqueOrderOrderNumber")
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    BoutiqueOrderDTO toDtoBoutiqueOrderOrderNumber(BoutiqueOrder boutiqueOrder);
}
