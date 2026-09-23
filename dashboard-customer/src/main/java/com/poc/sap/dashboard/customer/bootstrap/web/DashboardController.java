package com.poc.sap.dashboard.customer.bootstrap.web;

import com.poc.sap.common.security.AccessScope;
import com.poc.sap.common.security.ApiRoles;
import com.poc.sap.common.security.PiiMasker;
import com.poc.sap.dashboard.customer.application.GetCustomerOverview;
import com.poc.sap.dashboard.customer.application.GetHistory;
import com.poc.sap.dashboard.customer.application.GetHistoryDiff;
import com.poc.sap.dashboard.customer.application.GetOpenAlerts;
import com.poc.sap.dashboard.customer.application.SearchCustomers;
import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.CustomerState;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vista web del dashboard-customer (UI-001 H-4 AC-1, AC-2, AC-3):
 * {@code /customers/{id}} pinta la cabecera + tabs + contenido (vista por
 * entidad), {@code /customers/{id}/history} y {@code /customers/{id}/history/diff}
 * el historico, {@code /customers/search} la busqueda, {@code /customers/{id}/state}
 * el detalle del estado actual.
 *
 * <p>La PII se enmascara para {@code sap-external-read}
 * (sdd/common/seguridad-api.md R-4): el {@link AccessScope} decide si el
 * lector ve el snapshot completo o reducido.
 */
@Controller
@RequestMapping("/customers")
public class DashboardController {

    private final GetCustomerOverview overview;
    private final GetHistory history;
    private final GetHistoryDiff historyDiff;
    private final SearchCustomers searchCustomers;
    @SuppressWarnings("unused")
    private final GetOpenAlerts openAlerts; // para cuando se anada la pestana Alerts en el layout
    private final AccessScope scope;

    public DashboardController(GetCustomerOverview overview, GetHistory history,
                              GetHistoryDiff historyDiff, SearchCustomers searchCustomers,
                              GetOpenAlerts openAlerts, AccessScope scope) {
        this.overview = overview;
        this.history = history;
        this.historyDiff = historyDiff;
        this.searchCustomers = searchCustomers;
        this.openAlerts = openAlerts;
        this.scope = scope;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.EXTERNAL_READ + "','" + ApiRoles.WRITE + "','" + ApiRoles.ADMIN + "','" + ApiRoles.SUPERADMIN + "')")
    public String customer(@PathVariable String id, Model model) {
        CustomerState state = overview.stateOf(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        CustomerSnapshot snapshot = overview.snapshot(id).orElseThrow();
        boolean full = scope.canSeeSensitiveData();
        model.addAttribute("state", full ? state : maskState(state));
        model.addAttribute("snapshot", full ? snapshot : maskSnapshot(snapshot));
        model.addAttribute("piiMasked", !full);
        model.addAttribute("entityId", id);
        model.addAttribute("currentPath", "/customers/" + id);
        return "customer";
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.EXTERNAL_READ + "','" + ApiRoles.WRITE + "','" + ApiRoles.ADMIN + "','" + ApiRoles.SUPERADMIN + "')")
    public String history(@PathVariable String id, Model model) {
        List<?> versions = history.of(id);
        model.addAttribute("versions", versions);
        model.addAttribute("entityId", id);
        model.addAttribute("currentPath", "/customers/" + id + "/history");
        return "history";
    }

    @GetMapping("/{id}/history/diff")
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.WRITE + "','" + ApiRoles.ADMIN + "','" + ApiRoles.SUPERADMIN + "')")
    public String diff(@PathVariable String id,
                       @RequestParam(required = false) String fromHash,
                       @RequestParam(required = false) String toHash,
                       Model model) {
        GetHistoryDiff.Diff d = historyDiff.diff(id, fromHash, toHash);
        model.addAttribute("diff", d);
        model.addAttribute("entityId", id);
        model.addAttribute("currentPath", "/customers/" + id + "/history/diff");
        return "diff";
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.EXTERNAL_READ + "','" + ApiRoles.WRITE + "','" + ApiRoles.ADMIN + "','" + ApiRoles.SUPERADMIN + "')")
    public String search(@RequestParam(name = "q", required = false) String q, Model model) {
        List<CustomerSnapshot> matches = searchCustomers.byTaxId(q);
        model.addAttribute("matches", matches);
        model.addAttribute("query", q == null ? "" : q);
        model.addAttribute("piiMasked", !scope.canSeeSensitiveData());
        model.addAttribute("currentPath", "/customers/search");
        return "search";
    }

    @GetMapping("/{id}/state")
    @PreAuthorize("hasAnyRole('" + ApiRoles.READ + "','" + ApiRoles.EXTERNAL_READ + "','" + ApiRoles.WRITE + "','" + ApiRoles.ADMIN + "','" + ApiRoles.SUPERADMIN + "')")
    public String state(@PathVariable String id, Model model) {
        CustomerState state = overview.stateOf(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("state", state);
        model.addAttribute("entityId", id);
        model.addAttribute("piiMasked", !scope.canSeeSensitiveData());
        return "state";
    }

    private static CustomerState maskState(CustomerState s) {
        Map<String, com.poc.sap.dashboard.customer.domain.FeatureState> out = new LinkedHashMap<>();
        s.features().forEach((name, line) -> out.put(name, maskFeatureState(line)));
        return new CustomerState(s.entityId(),
                maskFeatureState(s.aggregate()),
                out,
                maskTrace(s.lastCycle()));
    }

    private static com.poc.sap.dashboard.customer.domain.FeatureState maskFeatureState(
            com.poc.sap.dashboard.customer.domain.FeatureState l) {
        if (l == null) return null;
        return new com.poc.sap.dashboard.customer.domain.FeatureState(l.feature(), l.state(),
                l.payloadHash(), l.cycleId(), PiiMasker.maskDetail(l.detail()), l.at());
    }

    private static com.poc.sap.dashboard.customer.domain.CycleTrace maskTrace(
            com.poc.sap.dashboard.customer.domain.CycleTrace t) {
        if (t == null) return null;
        List<com.poc.sap.dashboard.customer.domain.CycleTrace.Step> steps = t.steps().stream()
                .map(p -> new com.poc.sap.dashboard.customer.domain.CycleTrace.Step(
                        p.line(), p.state(), PiiMasker.maskDetail(p.detail()), p.at()))
                .toList();
        return new com.poc.sap.dashboard.customer.domain.CycleTrace(t.cycleId(), t.payloadHash(),
                t.startedAt(), t.endedAt(), steps);
    }

    private static CustomerSnapshot maskSnapshot(CustomerSnapshot s) {
        if (s == null) return null;
        return new CustomerSnapshot(s.entityId(), s.code(), s.name(), s.status(),
                maskAddr(s.address()), maskFiscal(s.fiscal()), maskContact(s.contact()), maskBanking(s.banking()));
    }

    private static com.poc.sap.dashboard.customer.domain.AddressData maskAddr(
            com.poc.sap.dashboard.customer.domain.AddressData a) { return a; }

    private static com.poc.sap.dashboard.customer.domain.FiscalData maskFiscal(
            com.poc.sap.dashboard.customer.domain.FiscalData f) {
        if (f == null) return null;
        return new com.poc.sap.dashboard.customer.domain.FiscalData(
                PiiMasker.mask(f.taxId()), PiiMasker.mask(f.vatNumber()), f.legalName(), f.taxResidency());
    }

    private static com.poc.sap.dashboard.customer.domain.ContactData maskContact(
            com.poc.sap.dashboard.customer.domain.ContactData c) {
        if (c == null) return null;
        return new com.poc.sap.dashboard.customer.domain.ContactData(
                PiiMasker.maskEmail(c.email()), PiiMasker.maskPhone(c.phone()),
                PiiMasker.maskPhone(c.fax()), c.website());
    }

    private static com.poc.sap.dashboard.customer.domain.BankingData maskBanking(
            com.poc.sap.dashboard.customer.domain.BankingData b) {
        if (b == null) return null;
        return new com.poc.sap.dashboard.customer.domain.BankingData(
                PiiMasker.mask(b.iban()), b.bic(),
                b.mandateIds() == null ? List.of()
                        : b.mandateIds().stream().map(PiiMasker::maskPhone).toList());
    }
}
