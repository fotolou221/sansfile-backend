package com.sansfile.app.web.filter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sansfile.app.IntegrationTest;
import com.sansfile.app.domain.PlatformSettings;
import com.sansfile.app.repository.PlatformSettingsRepository;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.service.custom.maintenance.MaintenanceModeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link MaintenanceModeFilter}.
 */
@AutoConfigureMockMvc
@IntegrationTest
@Transactional
class MaintenanceModeFilterIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformSettingsRepository platformSettingsRepository;

    @Autowired
    private MaintenanceModeService maintenanceModeService;

    private PlatformSettings settings;

    @BeforeEach
    void enableMaintenance() {
        settings = platformSettingsRepository
            .findAll()
            .stream()
            .findFirst()
            .orElseGet(() ->
                new PlatformSettings()
                    .appName("SansFile")
                    .contactEmail("contact@sansfile.sn")
                    .contactPhone("+221")
                    .allowRelativeBooking(true)
            );
        settings.setMaintenanceMode(true);
        settings = platformSettingsRepository.saveAndFlush(settings);
        maintenanceModeService.evict();
    }

    @AfterEach
    void forgetCachedState() {
        // La transaction de test est annulée : l'état mis en cache ne doit pas déborder sur les autres tests
        maintenanceModeService.evict();
    }

    @Test
    void anonymousSmsLoginIsRefused() throws Exception {
        mockMvc
            .perform(
                post("/api/auth/otp/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phone\":\"+221770000000\",\"role\":\"CLIENT\"}")
            )
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "120"))
            .andExpect(jsonPath("$.maintenance").value(true))
            .andExpect(jsonPath("$.error").value(MaintenanceModeFilter.MESSAGE));
    }

    @Test
    @WithMockUser(authorities = { AuthoritiesConstants.USER, AuthoritiesConstants.CLIENT })
    void clientCannotBookATicket() throws Exception {
        mockMvc
            .perform(post("/api/tickets/book-multiple").contentType(MediaType.APPLICATION_JSON).content("{\"salonId\":1}"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    @WithMockUser(authorities = { AuthoritiesConstants.USER, AuthoritiesConstants.CLIENT })
    void readsStayOpenSoTheAppCanShowTheMaintenancePage() throws Exception {
        mockMvc.perform(get("/api/platform-settings")).andExpect(status().isOk()).andExpect(jsonPath("$[0].maintenanceMode").value(true));
    }

    @Test
    void adminCanStillSignIn() throws Exception {
        mockMvc
            .perform(
                post("/api/authenticate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"nobody@test.sn\",\"password\":\"wrong-password\"}")
            )
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = AuthoritiesConstants.ADMIN)
    void adminCanWorkAndLiftTheMaintenance() throws Exception {
        settings.setMaintenanceMode(false);
        mockMvc
            .perform(
                put("/api/platform-settings/{id}", settings.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"id\":%d,\"appName\":\"SansFile\",\"contactEmail\":\"contact@sansfile.sn\",\"contactPhone\":\"+221\",\"allowRelativeBooking\":true,\"maintenanceMode\":false}".formatted(
                            settings.getId()
                        )
                    )
            )
            .andExpect(status().isOk());

        // Maintenance levée : les écritures passent de nouveau (numéro invalide → 400, plus 503)
        mockMvc
            .perform(post("/api/auth/otp/send").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"123\",\"role\":\"CLIENT\"}"))
            .andExpect(status().isBadRequest());
    }
}
