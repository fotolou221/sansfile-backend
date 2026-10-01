package com.sansfile.app.web.rest.custom;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sansfile.app.IntegrationTest;
import com.sansfile.app.security.AuthoritiesConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@link AdminMonitoringResource}.
 */
@AutoConfigureMockMvc
@IntegrationTest
class AdminMonitoringResourceIT {

    private static final String MONITORING_URL = "/api/admin/monitoring";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(authorities = AuthoritiesConstants.ADMIN)
    void adminGetsPlatformHealth() throws Exception {
        mockMvc
            .perform(get(MONITORING_URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(oneOf("UP", "WARN")))
            .andExpect(jsonPath("$.components", hasSize(5)))
            .andExpect(jsonPath("$.components[?(@.key == 'database')].status").value("UP"))
            .andExpect(jsonPath("$.components[?(@.key == 'redis')].status").value("UP"))
            // Tests : fournisseur « mock », aucun appel à SendText
            .andExpect(jsonPath("$.sms.live").value(false))
            .andExpect(jsonPath("$.components[?(@.key == 'sms')].status").value("INFO"))
            .andExpect(jsonPath("$.sms.otpToday").isNumber())
            // Tests : aucune clé Cloudinary, l'API Cloudinary n'est jamais appelée
            .andExpect(jsonPath("$.storage.cloudinaryConfigured").value(false))
            .andExpect(jsonPath("$.storage.localFiles").isNumber())
            .andExpect(jsonPath("$.components[?(@.key == 'storage')].status").value(contains(oneOf("UP", "WARN"))))
            .andExpect(jsonPath("$.application.uptimeSeconds").isNumber())
            .andExpect(jsonPath("$.system.heapMaxMb").isNumber())
            .andExpect(jsonPath("$.database.maxConnections").isNumber());
    }

    @Test
    @WithMockUser(authorities = { AuthoritiesConstants.USER, AuthoritiesConstants.CLIENT })
    void clientCannotSeeMonitoring() throws Exception {
        mockMvc.perform(get(MONITORING_URL)).andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotSeeMonitoring() throws Exception {
        mockMvc.perform(get(MONITORING_URL)).andExpect(status().isUnauthorized());
    }
}
