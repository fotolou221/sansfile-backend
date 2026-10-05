package com.sansfile.app.web.rest.custom;

import com.sansfile.app.domain.enumeration.AgentAction;
import com.sansfile.app.service.custom.agent.AgentAccountService;
import com.sansfile.app.service.custom.agent.AgentAccountService.AgentCredentials;
import com.sansfile.app.service.custom.agent.AgentAccountService.AgentForm;
import com.sansfile.app.service.custom.agent.AgentAccountService.AgentSummary;
import com.sansfile.app.service.custom.agent.AgentActivityService;
import com.sansfile.app.service.custom.agent.AgentActivityService.ActivityView;
import com.sansfile.app.service.dto.SalonDTO;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Console d'administration : comptes des agents de terrain et journal de leurs actions.
 * Réservé aux administrateurs (règle générale « /api/** » de la configuration de sécurité).
 */
@Tag(name = "Admin — Agents de terrain", description = "Comptes agents, salons inscrits et journal des actions")
@RestController
@RequestMapping("/api/admin")
public class AdminAgentResource {

    private static final int MAX_PAGE_SIZE = 200;

    private final AgentAccountService agentAccountService;
    private final AgentActivityService agentActivityService;

    public AdminAgentResource(AgentAccountService agentAccountService, AgentActivityService agentActivityService) {
        this.agentAccountService = agentAccountService;
        this.agentActivityService = agentActivityService;
    }

    public record ActivationRequest(boolean activated) {}

    @GetMapping("/agents")
    public List<AgentSummary> listAgents() {
        return agentAccountService.listAgents();
    }

    /** Crée le compte avec le mot de passe provisoire, renvoyé une fois pour être transmis à l'agent. */
    @PostMapping("/agents")
    public ResponseEntity<AgentCredentials> createAgent(@RequestBody AgentForm form) {
        AgentCredentials created = agentAccountService.createAgent(form);
        return ResponseEntity.created(URI.create("/api/admin/agents/" + created.agent().id())).body(created);
    }

    @GetMapping("/agents/{id}")
    public AgentSummary getAgent(@PathVariable("id") Long id) {
        return agentAccountService.getAgent(id);
    }

    @PutMapping("/agents/{id}")
    public AgentSummary updateAgent(@PathVariable("id") Long id, @RequestBody AgentForm form) {
        return agentAccountService.updateAgent(id, form);
    }

    @PostMapping("/agents/{id}/reset-password")
    public AgentCredentials resetPassword(@PathVariable("id") Long id) {
        return agentAccountService.resetPassword(id);
    }

    @PutMapping("/agents/{id}/activation")
    public AgentSummary setActivation(@PathVariable("id") Long id, @RequestBody ActivationRequest request) {
        return agentAccountService.setActivated(id, request.activated());
    }

    @GetMapping("/agents/{id}/salons")
    public List<SalonDTO> agentSalons(@PathVariable("id") Long id) {
        return agentAccountService.salonsOf(id);
    }

    /** Journal des agents, du plus récent au plus ancien ; total dans l'en-tête X-Total-Count. */
    @GetMapping("/agent-activities")
    public ResponseEntity<List<ActivityView>> activities(
        @RequestParam(value = "agentId", required = false) Long agentId,
        @RequestParam(value = "action", required = false) AgentAction action,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "50") int size
    ) {
        Page<ActivityView> result = agentActivityService.search(
            agentId,
            action,
            PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))
        );
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Total-Count", Long.toString(result.getTotalElements()));
        return ResponseEntity.ok().headers(headers).body(result.getContent());
    }
}
