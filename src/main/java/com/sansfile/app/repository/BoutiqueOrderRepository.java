package com.sansfile.app.repository;

import com.sansfile.app.domain.BoutiqueOrder;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the BoutiqueOrder entity.
 */
@Repository
public interface BoutiqueOrderRepository extends JpaRepository<BoutiqueOrder, Long>, JpaSpecificationExecutor<BoutiqueOrder> {
    @Query(
        "select boutiqueOrder from BoutiqueOrder boutiqueOrder where boutiqueOrder.user.login = ?#{authentication.name} order by boutiqueOrder.createdDate desc"
    )
    List<BoutiqueOrder> findByUserIsCurrentUser();

    List<BoutiqueOrder> findByUserIdOrderByCreatedDateDesc(Long userId);

    List<BoutiqueOrder> findTop10ByOrderByCreatedDateDesc();

    java.util.Optional<BoutiqueOrder> findByOrderNumber(String orderNumber);

    /** Facture du partenaire, ouverte par son lien. */
    java.util.Optional<BoutiqueOrder> findOneByInvoiceToken(String invoiceToken);

    boolean existsByPartnerId(Long partnerId);

    boolean existsByLocalityId(Long localityId);

    @Query("select o.localityId, count(o) from BoutiqueOrder o where o.localityId is not null group by o.localityId")
    List<Object[]> countOrdersByLocality();
}
