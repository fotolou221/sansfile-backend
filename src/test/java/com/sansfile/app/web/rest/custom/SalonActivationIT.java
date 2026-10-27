package com.sansfile.app.web.rest.custom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sansfile.app.IntegrationTest;
import com.sansfile.app.domain.Salon;
import com.sansfile.app.domain.User;
import com.sansfile.app.domain.enumeration.SalonStatus;
import com.sansfile.app.repository.SalonRepository;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.service.custom.queue.QueueEngineService;
import com.sansfile.app.service.custom.queue.QueueEngineService.BeneficiaryItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Salon désactivé par l'administration : invisible des clients, plus aucun ticket ; réactivé : de nouveau visible.
 */
@AutoConfigureMockMvc
@IntegrationTest
@Transactional
class SalonActivationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private QueueEngineService queueEngineService;

    @Test
    void deactivatedSalonIsHiddenFromClientsAndTakesNoTicket() throws Exception {
        Salon salon = salonRepository.saveAndFlush(newSalon());
        int id = salon.getId().intValue();

        setActive(salon, false).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        // Clients (et visiteurs) : le salon n'apparaît plus ; l'administration le voit toujours
        mockMvc.perform(get("/api/salons").param("size", "1000")).andExpect(jsonPath("$[*].id").value(not(hasItem(id))));
        mockMvc
            .perform(get("/api/salons").param("size", "1000").with(admin()))
            .andExpect(jsonPath("$[?(@.id == " + id + ")].active").value(hasItem(false)));
        assertThatThrownBy(() ->
            queueEngineService.bookTickets(salon.getId(), (User) null, List.of(new BeneficiaryItem("Awa", "CUSTOM", null, null)))
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("n'accepte plus");

        // Réactivé : de nouveau visible et ouvert aux tickets
        setActive(salon, true).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(get("/api/salons").param("size", "1000")).andExpect(jsonPath("$[*].id").value(hasItem(id)));
        queueEngineService.bookTickets(salon.getId(), (User) null, List.of(new BeneficiaryItem("Awa", "CUSTOM", null, null)));
    }

    @Test
    void onlyTheAdministrationDeactivatesASalon() throws Exception {
        Salon salon = salonRepository.saveAndFlush(newSalon());
        mockMvc
            .perform(
                put("/api/salons/" + salon.getId() + "/activation")
                    .with(user("+221770000001").authorities(new SimpleGrantedAuthority(AuthoritiesConstants.COIFFEUR)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"active\":false}")
            )
            .andExpect(status().isForbidden());
    }

    private ResultActions setActive(Salon salon, boolean active) throws Exception {
        return mockMvc.perform(
            put("/api/salons/" + salon.getId() + "/activation")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":" + active + "}")
        );
    }

    private static Salon newSalon() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Salon salon = new Salon();
        salon.setName("Salon " + suffix);
        salon.setSlug("salon-" + suffix);
        salon.setLocation("Dakar");
        salon.setDistrict("Médina");
        salon.setStatus(SalonStatus.OPEN);
        salon.setActive(true);
        salon.setPeopleWaiting(0);
        salon.setEstimatedWaitMinutes(0);
        salon.setCreatedDate(Instant.now());
        salon.setLastModifiedDate(Instant.now());
        return salon;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return user("admin-salons-it").authorities(new SimpleGrantedAuthority(AuthoritiesConstants.ADMIN));
    }
}
