package com.sansfile.app.repository;

import com.sansfile.app.domain.PartnerProduct;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PartnerProductRepository extends JpaRepository<PartnerProduct, Long> {
    @Query("select pp from PartnerProduct pp join fetch pp.product where pp.partner.id = :partnerId")
    List<PartnerProduct> findAllByPartnerIdWithProduct(@Param("partnerId") Long partnerId);

    Optional<PartnerProduct> findOneByPartnerIdAndProductId(Long partnerId, Long productId);

    /** Offre verrouillée le temps de la transaction : deux confirmations simultanées ne vendent pas le même stock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pp from PartnerProduct pp join fetch pp.product where pp.partner.id = :partnerId and pp.product.id = :productId")
    Optional<PartnerProduct> findForUpdate(@Param("partnerId") Long partnerId, @Param("productId") Long productId);

    /** Produits commandables dans une localité : partenaire actif, offre disponible avec du stock, produit en vente. */
    @Query(
        "select pp.product.id from PartnerProduct pp " +
            "where pp.partner.locality.id = :localityId and pp.partner.active = true " +
            "and pp.available = true and pp.stockQuantity > 0 and pp.product.inStock = true"
    )
    List<Long> findAvailableProductIdsByLocalityId(@Param("localityId") Long localityId);

    /** Offres dont le prix de gros dépasserait un prix de vente donné (contrôle avant de baisser un prix). */
    @Query("select pp from PartnerProduct pp join fetch pp.partner where pp.product.id = :productId and pp.wholesalePrice > :price")
    List<PartnerProduct> findAllByProductIdAndWholesalePriceAbove(@Param("productId") Long productId, @Param("price") Long price);

    @Query(
        "select pp.partner.id, count(pp) from PartnerProduct pp where pp.available = true and pp.stockQuantity > 0 group by pp.partner.id"
    )
    List<Object[]> countAvailableByPartner();
}
