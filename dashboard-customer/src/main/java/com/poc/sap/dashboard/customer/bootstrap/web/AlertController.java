package com.poc.sap.dashboard.customer.bootstrap.web;

import com.poc.sap.common.security.AccessScope;
import com.poc.sap.common.security.ApiRoles;
import com.poc.sap.dashboard.customer.application.AcknowledgeAlert;
import com.poc.sap.dashboard.customer.application.GetOpenAlerts;
import com.poc.sap.dashboard.customer.domain.Alert;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/**
 * Gestion de alertas operativas (UI-001 H-4 F-9, AC-6).
 *
 * <p>{@code POST /customers/alerts/{id}/ack}: solo {@code sap-write} (y por
 * encima en la jerarquia). Persiste {@code ackedAt}/{@code ackedBy} en Mongo.
 * El actor se compone del rol del {@link Authentication} y su name: formato
 * {@code sap-write:ana}. En un despliegue real el {@code Authentication} es
 * un {@code JwtAuthenticationToken} con subject = name.
 *
 * <p>{@code GET /customers/alerts/open}: cualquier rol de lectura. Pinta la
 * lista de alertas pendientes de reconocer.
 */
@Controller
@RequestMapping("/customers/alerts")
public class AlertController {

    private final AcknowledgeAlert acknowledge;
    private final GetOpenAlerts openAlerts;
    private final AccessScope scope;

    public AlertController(AcknowledgeAlert acknowledge, GetOpenAlerts openAlerts, AccessScope scope) {
        this.acknowledge = acknowledge;
        this.openAlerts = openAlerts;
        this.scope = scope;
    }

    @PostMapping("/{id}/ack")
    @PreAuthorize("hasAnyRole('" + ApiRoles.WRITE + "','" + ApiRoles.ADMIN + "','" + ApiRoles.SUPERADMIN + "')")
    public String ack(@PathVariable String id, Authentication auth) {
        String actor = actorFrom(auth);
        acknowledge.acknowledge(id, actor);
        return "redirect:/customers/alerts/open";
    }

    @GetMapping("/open")
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.EXTERNAL_READ + "','" + ApiRoles.WRITE + "','" + ApiRoles.ADMIN + "','" + ApiRoles.SUPERADMIN + "')")
    public String open(Model model) {
        List<Alert> alerts = openAlerts.all();
        model.addAttribute("alerts", alerts);
        model.addAttribute("piiMasked", !scope.canSeeSensitiveData());
        model.addAttribute("currentPath", "/customers/alerts/open");
        return "alerts";
    }

    private static String actorFrom(Authentication auth) {
        String highest = highestRole(auth);
        String subject = auth == null || auth.getName() == null ? "anonymous" : auth.getName();
        if (subject.isBlank()) {
            subject = "anonymous";
        }
        return highest + ":" + subject;
    }

    private static String highestRole(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) return "sap-write";
        for (String r : new String[]{"SAP_SUPERADMIN", "SAP_ADMIN", "SAP_WRITE"}) {
            if (auth.getAuthorities().stream().anyMatch(a -> ("ROLE_" + r).equals(a.getAuthority()))) {
                return r.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            }
        }
        return "sap-write";
    }
}
