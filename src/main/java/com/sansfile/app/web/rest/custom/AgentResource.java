package com.sansfile.app.web.rest.custom;

import com.sansfile.app.service.custom.agent.AgentAccountService;
import com.sansfile.app.service.custom.agent.AgentAccountService.AgentDashboard;
import com.sansfile.app.service.custom.agent.AgentAccountService.AgentProfile;
import com.sansfile.app.service.dto.SalonDTO;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Espace des agents de terrain (rôle ROLE_AGENT). L'inscription d'un salon passe par
 * {@code POST /api/salons}, qui le rattache automatiquement à l'agent connecté.
 */
@Tag(name = "Agents de terrain", description = "Espace agent : profil, mot de passe, salons inscrits")
@RestController
@RequestMapping("/api/agent")
public class AgentResource {

    private final AgentAccountService agentAccountService;

    public AgentResource(AgentAccountService agentAccountService) {
        this.agentAccountService = agentAccountService;
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword) {}

    /** Profil de l'agent, y compris l'obligation de remplacer le mot de passe provisoire. */
    @GetMapping("/me")
    public AgentProfile me() {
        return agentAccountService.currentProfile();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@RequestBody PasswordChangeRequest request) {
        agentAccountService.changePassword(request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** Trace la déconnexion dans le journal (les jetons sont effacés par l'application). */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        agentAccountService.recordLogout();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dashboard")
    public AgentDashboard dashboard() {
        return agentAccountService.dashboard();
    }

    /** Salons inscrits par l'agent connecté, du plus récent au plus ancien. */
    @GetMapping("/salons")
    public List<SalonDTO> mySalons() {
        return agentAccountService.mySalons();
    }

    /** Tous les salons des localités de l'agent (y compris ceux inscrits par d'autres), par nom. */
    @GetMapping("/zone-salons")
    public List<SalonDTO> zoneSalons() {
        return agentAccountService.zoneSalons();
    }
}
