package com.sansfile.app.service.impl;

import com.sansfile.app.domain.BoutiqueOrder;
import com.sansfile.app.repository.BoutiqueOrderRepository;
import com.sansfile.app.service.BoutiqueOrderService;
import com.sansfile.app.service.custom.realtime.RealtimeEventService;
import com.sansfile.app.service.dto.BoutiqueOrderDTO;
import com.sansfile.app.service.mapper.BoutiqueOrderMapper;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service Implementation for managing {@link com.sansfile.app.domain.BoutiqueOrder}.
 */
@Service
@Transactional
public class BoutiqueOrderServiceImpl implements BoutiqueOrderService {

    private static final Logger LOG = LoggerFactory.getLogger(BoutiqueOrderServiceImpl.class);

    private final BoutiqueOrderRepository boutiqueOrderRepository;
    private final BoutiqueOrderMapper boutiqueOrderMapper;
    private final RealtimeEventService realtimeEventService;

    public BoutiqueOrderServiceImpl(
        BoutiqueOrderRepository boutiqueOrderRepository,
        BoutiqueOrderMapper boutiqueOrderMapper,
        RealtimeEventService realtimeEventService
    ) {
        this.boutiqueOrderRepository = boutiqueOrderRepository;
        this.boutiqueOrderMapper = boutiqueOrderMapper;
        this.realtimeEventService = realtimeEventService;
    }

    @Override
    public BoutiqueOrderDTO save(BoutiqueOrderDTO boutiqueOrderDTO) {
        LOG.debug("Request to save BoutiqueOrder : {}", boutiqueOrderDTO);
        BoutiqueOrder boutiqueOrder = boutiqueOrderMapper.toEntity(boutiqueOrderDTO);
        boutiqueOrder = boutiqueOrderRepository.save(boutiqueOrder);
        BoutiqueOrderDTO result = boutiqueOrderMapper.toDto(boutiqueOrder);
        broadcast("ORDER_CREATED", result);
        return result;
    }

    @Override
    public BoutiqueOrderDTO update(BoutiqueOrderDTO boutiqueOrderDTO) {
        LOG.debug("Request to update BoutiqueOrder : {}", boutiqueOrderDTO);
        BoutiqueOrder boutiqueOrder = boutiqueOrderMapper.toEntity(boutiqueOrderDTO);
        boutiqueOrder = boutiqueOrderRepository.save(boutiqueOrder);
        BoutiqueOrderDTO result = boutiqueOrderMapper.toDto(boutiqueOrder);
        broadcast("ORDER_UPDATED", result);
        return result;
    }

    @Override
    public Optional<BoutiqueOrderDTO> partialUpdate(BoutiqueOrderDTO boutiqueOrderDTO) {
        LOG.debug("Request to partially update BoutiqueOrder : {}", boutiqueOrderDTO);

        return boutiqueOrderRepository
            .findById(boutiqueOrderDTO.getId())
            .map(existingBoutiqueOrder -> {
                boutiqueOrderMapper.partialUpdate(existingBoutiqueOrder, boutiqueOrderDTO);
                return existingBoutiqueOrder;
            })
            .map(boutiqueOrderRepository::save)
            .map(boutiqueOrderMapper::toDto)
            .map(result -> {
                broadcast("ORDER_UPDATED", result);
                return result;
            });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BoutiqueOrderDTO> findOne(Long id) {
        LOG.debug("Request to get BoutiqueOrder : {}", id);
        return boutiqueOrderRepository.findById(id).map(boutiqueOrderMapper::toDto);
    }

    @Override
    public void delete(Long id) {
        LOG.debug("Request to delete BoutiqueOrder : {}", id);
        boutiqueOrderRepository.deleteById(id);
        broadcast("ORDER_DELETED", Map.of("id", id));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return boutiqueOrderRepository.existsById(id);
    }

    private void broadcast(String event, Object payload) {
        try {
            realtimeEventService.broadcast(event, payload);
        } catch (Exception e) {
            LOG.warn("Diffusion temps réel {} impossible : {}", event, e.getMessage());
        }
    }
}
