package com.sansfile.app.web.rest.custom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sansfile.app.IntegrationTest;
import com.sansfile.app.domain.CoiffeurProfile;
import com.sansfile.app.domain.Salon;
import com.sansfile.app.domain.User;
import com.sansfile.app.domain.enumeration.SalonStatus;
import com.sansfile.app.repository.CoiffeurProfileRepository;
import com.sansfile.app.repository.SalonRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.service.custom.queue.QueueEngineService;
import com.sansfile.app.service.dto.TicketDTO;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Client direct ajouté par le coiffeur : jamais avec le numéro du salon ou du coiffeur lui-même. */
@IntegrationTest
@Transactional
class WalkInIT {

    private static final String SALON_PHONE = "+221338001122";
    private static final String COIFFEUR_PHONE = "+221778888801";

    @Autowired
    private QueueEngineService queueEngineService;

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private CoiffeurProfileRepository coiffeurProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void coiffeurCannotQueueHisOwnOrTheSalonNumber() {
        Salon salon = salonWithCoiffeur();

        // Même numéro saisi autrement (sans indicatif, avec espaces)
        assertThatThrownBy(() -> queueEngineService.addWalkInClient(salon.getId(), "Moi", "77 888 88 01"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("coiffeur");
        assertThatThrownBy(() -> queueEngineService.addWalkInClient(salon.getId(), "Salon", SALON_PHONE)).isInstanceOf(
            IllegalArgumentException.class
        );

        TicketDTO client = queueEngineService.addWalkInClient(salon.getId(), "Awa", "+221771112233");
        assertThat(client.getOwnerName()).isEqualTo("Awa");
        TicketDTO withoutPhone = queueEngineService.addWalkInClient(salon.getId(), "Modou", null);
        assertThat(withoutPhone.getOwnerName()).isEqualTo("Modou");
    }

    private Salon salonWithCoiffeur() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Salon salon = new Salon();
        salon.setName("Salon " + suffix);
        salon.setSlug("salon-" + suffix);
        salon.setLocation("Dakar");
        salon.setDistrict("Médina");
        salon.setPhone(SALON_PHONE);
        salon.setStatus(SalonStatus.OPEN);
        salon.setActive(true);
        salon.setPeopleWaiting(0);
        salon.setEstimatedWaitMinutes(0);
        salon.setCreatedDate(Instant.now());
        salon.setLastModifiedDate(Instant.now());
        salon = salonRepository.saveAndFlush(salon);

        User coiffeur = new User();
        coiffeur.setLogin(COIFFEUR_PHONE);
        coiffeur.setPhone(COIFFEUR_PHONE);
        coiffeur.setPassword("$2a$10$" + "x".repeat(53));
        coiffeur.setFirstName("Barbier");
        coiffeur.setActivated(true);
        coiffeur.setLangKey("fr");
        coiffeur = userRepository.saveAndFlush(coiffeur);

        CoiffeurProfile profile = new CoiffeurProfile();
        profile.setName("Barbier Test");
        profile.setPhone(COIFFEUR_PHONE);
        profile.setActive(true);
        profile.setSalon(salon);
        profile.setUser(coiffeur);
        profile.setCreatedDate(Instant.now());
        coiffeurProfileRepository.saveAndFlush(profile);
        return salon;
    }
}
