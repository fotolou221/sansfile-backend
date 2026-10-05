package com.sansfile.app.web.rest.custom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sansfile.app.IntegrationTest;
import com.sansfile.app.domain.AgentActivity;
import com.sansfile.app.domain.Salon;
import com.sansfile.app.domain.User;
import com.sansfile.app.domain.enumeration.AgentAction;
import com.sansfile.app.repository.AgentActivityRepository;
import com.sansfile.app.repository.CoiffeurProfileRepository;
import com.sansfile.app.repository.SalonRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.service.custom.agent.AgentAccountService;
import com.sansfile.app.service.custom.agent.AgentAccountService.AgentForm;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Agents de terrain : comptes créés par l'admin, mot de passe provisoire à changer, salons inscrits
 * rattachés à l'agent, journal des actions et règles d'accès.
 * <p>
 * Non transactionnel : le journal est écrit dans sa propre transaction. Les données créées sont
 * supprimées après chaque test, et e-mails / numéros sont uniques à chaque exécution.
 */
@AutoConfigureMockMvc
@IntegrationTest
class AgentsIT {

    private static final String DEFAULT_PASSWORD = "sansfile2026@";
    private static final String NEW_PASSWORD = "Terrain2026!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private AgentAccountService agentAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private CoiffeurProfileRepository coiffeurProfileRepository;

    @Autowired
    private AgentActivityRepository activityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<Long> agentIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        activityRepository.deleteAll(
            activityRepository
                .findAll()
                .stream()
                .filter(a -> agentIds.contains(a.getAgentId()))
                .toList()
        );
        for (Long agentId : agentIds) {
            for (Salon salon : salonRepository.findAllByCreatedByAgentIdOrderByIdDesc(agentId)) {
                coiffeurProfileRepository.findBySalonId(salon.getId()).forEach(profile -> {
                    User owner = profile.getUser();
                    coiffeurProfileRepository.delete(profile);
                    if (owner != null) {
                        userRepository.deleteById(owner.getId());
                    }
                });
                salonRepository.delete(salon);
            }
            userRepository.deleteById(agentId);
        }
        agentIds.clear();
    }

    @Test
    void adminCreatesAgentWithTemporaryPassword() throws Exception {
        String email = uniqueEmail();
        String body = om.writeValueAsString(Map.of("firstName", "Awa", "lastName", "Ndiaye", "email", email.toUpperCase(), "phone", ""));

        String response = mockMvc
            .perform(post("/api/admin/agents").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.temporaryPassword").value(DEFAULT_PASSWORD))
            .andExpect(jsonPath("$.agent.email").value(email))
            .andExpect(jsonPath("$.agent.mustChangePassword").value(true))
            .andExpect(jsonPath("$.agent.activated").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long agentId = om.readTree(response).at("/agent/id").asLong();
        agentIds.add(agentId);

        User agent = userRepository.findOneWithAuthoritiesByLogin(email).orElseThrow();
        assertThat(agent.getAuthorities()).anyMatch(a -> AuthoritiesConstants.AGENT.equals(a.getName()));
        assertThat(passwordEncoder.matches(DEFAULT_PASSWORD, agent.getPassword())).isTrue();
        assertThat(actionsOf(agentId)).contains(AgentAction.ACCOUNT_CREATED);

        // Même adresse e-mail : refusé
        mockMvc
            .perform(post("/api/admin/agents").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("email-used"));
    }

    @Test
    void agentMustReplaceTemporaryPasswordBeforeWorking() throws Exception {
        String email = createAgent();

        // Connexion avec l'e-mail et le mot de passe provisoire (journalisée)
        login(email, DEFAULT_PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.id_token").isNotEmpty());
        login(email, "mauvais-mot-de-passe").andExpect(status().isUnauthorized());
        assertThat(actionsOf(agentIdOf(email))).contains(AgentAction.LOGIN, AgentAction.LOGIN_FAILED);

        mockMvc.perform(get("/api/agent/me").with(agent(email))).andExpect(jsonPath("$.mustChangePassword").value(true));
        mockMvc
            .perform(get("/api/agent/salons").with(agent(email)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("password-change-required"));

        changePassword(email, "faux", NEW_PASSWORD).andExpect(status().isBadRequest());
        changePassword(email, DEFAULT_PASSWORD, "court1").andExpect(status().isBadRequest());
        changePassword(email, DEFAULT_PASSWORD, DEFAULT_PASSWORD)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("same-password"));
        changePassword(email, DEFAULT_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        User agent = userRepository.findOneByLogin(email).orElseThrow();
        assertThat(agent.isMustChangePassword()).isFalse();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, agent.getPassword())).isTrue();
        assertThat(actionsOf(agent.getId())).contains(AgentAction.PASSWORD_CHANGED);

        mockMvc.perform(get("/api/agent/salons").with(agent(email))).andExpect(status().isOk());
        login(email, NEW_PASSWORD).andExpect(status().isOk());
        login(email, DEFAULT_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void salonRegisteredByAgentIsLinkedToHim() throws Exception {
        String email = createActiveAgent();
        Long agentId = agentIdOf(email);

        String created = mockMvc
            .perform(post("/api/salons").with(agent(email)).contentType(MediaType.APPLICATION_JSON).content(newSalon()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.createdByAgentId").value(agentId))
            // Réglages imposés : le coiffeur ouvrira son salon lui-même
            .andExpect(jsonPath("$.status").value("CLOSED"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.peopleWaiting").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long salonId = om.readTree(created).get("id").asLong();

        assertThat(activityRepository.findAll()).anyMatch(
            a -> agentId.equals(a.getAgentId()) && a.getAction() == AgentAction.SALON_CREATED && salonId.equals(a.getSalonId())
        );
        mockMvc.perform(get("/api/agent/salons").with(agent(email))).andExpect(jsonPath("$[*].id").value(hasItem(salonId.intValue())));
        mockMvc
            .perform(get("/api/agent/dashboard").with(agent(email)))
            .andExpect(jsonPath("$.salonsTotal").value(1))
            .andExpect(jsonPath("$.salonsThisMonth").value(1));
        mockMvc
            .perform(get("/api/admin/agents").with(admin()))
            .andExpect(jsonPath("$[?(@.id == " + agentId + ")].salonsCount").value(hasItem(1)));

        // L'agent corrige sa fiche, sans toucher au téléphone (compte du coiffeur) ni au statut
        String phoneBefore = salonRepository.findById(salonId).orElseThrow().getPhone();
        mockMvc
            .perform(
                patch("/api/salons/" + salonId)
                    .with(agent(email))
                    .contentType("application/merge-patch+json")
                    .content(
                        om.writeValueAsString(Map.of("id", salonId, "name", "Salon Corrigé", "phone", "+221700000000", "status", "OPEN"))
                    )
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Salon Corrigé"));
        Salon updated = salonRepository.findById(salonId).orElseThrow();
        assertThat(updated.getPhone()).isEqualTo(phoneBefore);
        assertThat(updated.getStatus().name()).isEqualTo("CLOSED");
        assertThat(actionsOf(agentId)).contains(AgentAction.SALON_UPDATED);

        // Un autre agent ne peut pas modifier ce salon
        String otherAgent = createActiveAgent();
        mockMvc
            .perform(
                patch("/api/salons/" + salonId)
                    .with(agent(otherAgent))
                    .contentType("application/merge-patch+json")
                    .content(om.writeValueAsString(Map.of("id", salonId, "name", "Piratage")))
            )
            .andExpect(status().isForbidden());

        // Une modification complète par l'admin ne détache pas le salon de son agent
        ObjectNode salonJson = (ObjectNode) om.readTree(
            mockMvc
                .perform(get("/api/salons/" + salonId).with(admin()))
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
        salonJson.putNull("createdByAgentId");
        salonJson.put("name", "Salon Revu Par Admin");
        mockMvc
            .perform(
                put("/api/salons/" + salonId)
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(salonJson.toString())
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.createdByAgentId").value(agentId));
        assertThat(salonRepository.findById(salonId).orElseThrow().getCreatedByAgentId()).isEqualTo(agentId);
    }

    @Test
    void adminResetsAndDisablesAgent() throws Exception {
        String email = createActiveAgent();
        Long agentId = agentIdOf(email);

        mockMvc
            .perform(post("/api/admin/agents/" + agentId + "/reset-password").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.temporaryPassword").value(DEFAULT_PASSWORD))
            .andExpect(jsonPath("$.agent.mustChangePassword").value(true));
        login(email, DEFAULT_PASSWORD).andExpect(status().isOk());

        mockMvc
            .perform(
                put("/api/admin/agents/" + agentId + "/activation")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"activated\":false}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activated").value(false));
        mockMvc
            .perform(get("/api/agent/me").with(agent(email)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("agent-disabled"));
        login(email, DEFAULT_PASSWORD).andExpect(status().isUnauthorized());

        mockMvc
            .perform(get("/api/admin/agent-activities").param("agentId", agentId.toString()).with(admin()))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Total-Count"))
            .andExpect(jsonPath("$[0].agentId").value(agentId))
            .andExpect(jsonPath("$[*].action").value(hasItem("ACCOUNT_DISABLED")))
            .andExpect(jsonPath("$[*].action").value(hasItem("PASSWORD_RESET")))
            .andExpect(jsonPath("$[*].action").value(hasItem("LOGIN_FAILED")));
    }

    @Test
    void agentSpaceAndAgentAdministrationAreProtected() throws Exception {
        String email = createActiveAgent();
        RequestPostProcessor client = user("client-agents-it").authorities(
            new org.springframework.security.core.authority.SimpleGrantedAuthority(AuthoritiesConstants.USER),
            new org.springframework.security.core.authority.SimpleGrantedAuthority(AuthoritiesConstants.CLIENT)
        );

        mockMvc.perform(get("/api/agent/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/agent/me").with(client)).andExpect(status().isForbidden());
        mockMvc
            .perform(post("/api/salons").with(client).contentType(MediaType.APPLICATION_JSON).content(newSalon()))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/agents").with(agent(email))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/agent-activities").with(agent(email))).andExpect(status().isForbidden());
    }

    // ── Outils ──────────────────────────────────────────────────

    private String createAgent() {
        String email = uniqueEmail();
        Long id = agentAccountService
            .createAgent(new AgentForm("Moussa", "Sarr", email, null))
            .agent()
            .id();
        agentIds.add(id);
        return email;
    }

    /** Agent dont le mot de passe provisoire a déjà été remplacé. */
    private String createActiveAgent() {
        String email = createAgent();
        User agent = userRepository.findOneByLogin(email).orElseThrow();
        agent.setMustChangePassword(false);
        agent.setPassword(passwordEncoder.encode(NEW_PASSWORD));
        userRepository.saveAndFlush(agent);
        return email;
    }

    private Long agentIdOf(String email) {
        return userRepository.findOneByLogin(email).orElseThrow().getId();
    }

    private List<AgentAction> actionsOf(Long agentId) {
        return activityRepository
            .findAll()
            .stream()
            .filter(a -> agentId.equals(a.getAgentId()))
            .map(AgentActivity::getAction)
            .toList();
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        // Adresse IP propre à ces tests : la limite de 10 connexions / 15 min par IP reste intacte pour les autres
        return mockMvc.perform(
            withIp(
                post("/api/authenticate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("username", email, "password", password)))
            )
        );
    }

    private org.springframework.test.web.servlet.ResultActions changePassword(String email, String current, String next) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("currentPassword", current);
        body.put("newPassword", next);
        return mockMvc.perform(
            withIp(
                post("/api/agent/password").with(agent(email)).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body))
            )
        );
    }

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr("10.77.0." + ThreadLocalRandom.current().nextInt(1, 250));
            return r;
        });
    }

    private String newSalon() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> salon = new HashMap<>();
        salon.put("name", "Salon Terrain " + suffix);
        salon.put("slug", "salon-terrain-" + suffix);
        salon.put("location", "Dakar");
        salon.put("district", "Médina");
        salon.put("phone", "+22176" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999));
        salon.put("ownerName", "Ibrahima Fall");
        // Ignorés pour un agent : remplacés par les réglages imposés
        salon.put("status", "OPEN");
        salon.put("active", false);
        salon.put("peopleWaiting", 12);
        return om.writeValueAsString(salon);
    }

    private static String uniqueEmail() {
        return "agent-" + UUID.randomUUID().toString().substring(0, 8) + "@terrain.sn";
    }

    private static RequestPostProcessor agent(String email) {
        return user(email).authorities(
            new org.springframework.security.core.authority.SimpleGrantedAuthority(AuthoritiesConstants.USER),
            new org.springframework.security.core.authority.SimpleGrantedAuthority(AuthoritiesConstants.AGENT)
        );
    }

    private static RequestPostProcessor admin() {
        return user("admin-agents-it").authorities(
            new org.springframework.security.core.authority.SimpleGrantedAuthority(AuthoritiesConstants.ADMIN)
        );
    }
}
