package com.sansfile.app.service;

import com.sansfile.app.service.dto.SalonDTO;
import java.util.Optional;

/**
 * Service Interface for managing {@link com.sansfile.app.domain.Salon}.
 */
public interface SalonService {
    /**
     * Save a salon.
     *
     * @param salonDTO the entity to save.
     * @return the persisted entity.
     */
    SalonDTO save(SalonDTO salonDTO);

    /**
     * Update a salon.
     *
     * @param salonDTO the entity to save.
     * @return the persisted entity.
     */
    SalonDTO update(SalonDTO salonDTO);

    /**
     * Partially update a salon.
     *
     * @param salonDTO the entity to update partially.
     * @return the persisted entity.
     */
    Optional<SalonDTO> partialUpdate(SalonDTO salonDTO);

    /**
     * Get one salon by id.
     *
     * @param id the id of the entity.
     * @return the entity.
     */
    Optional<SalonDTO> findOne(Long id);

    /**
     * Delete the salon by id.
     *
     * @param id the id of the entity.
     */
    void delete(Long id);

    /**
     * Check if a salon exists by id.
     *
     * @param id the id of the entity.
     * @return true if exists.
     */
    boolean existsById(Long id);
}
