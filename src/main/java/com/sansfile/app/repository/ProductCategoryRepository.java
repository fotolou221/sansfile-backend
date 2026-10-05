package com.sansfile.app.repository;

import com.sansfile.app.domain.ProductCategory;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the ProductCategory entity.
 */
@Repository
public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {}
