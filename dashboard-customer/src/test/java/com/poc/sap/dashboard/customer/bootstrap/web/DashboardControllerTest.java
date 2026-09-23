package com.poc.sap.dashboard.customer.bootstrap.web;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.security.AccessScope;
import com.poc.sap.dashboard.customer.application.AcknowledgeAlert;
import com.poc.sap.dashboard.customer.application.GetCustomerOverview;
import com.poc.sap.dashboard.customer.application.GetHistory;
import com.poc.sap.dashboard.customer.application.GetHistoryDiff;
import com.poc.sap.dashboard.customer.application.GetOpenAlerts;
import com.poc.sap.dashboard.customer.application.SearchCustomers;
import com.poc.sap.dashboard.customer.domain.CycleTrace;
import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.CustomerState;
import com.poc.sap.dashboard.customer.domain.FeatureState;
import com.poc.sap.dashboard.customer.domain.Alert;
import com.poc.sap.dashboard.customer.domain.CustomerFeature;
import com.poc.sap.dashboard.customer.domain.port.CustomerHistoryReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice web del DashboardController (UI-001 H-4 AC-1/AC-2). MockMvc
 * standalone (Spring 4.1 no incluye @WebMvcTest), con SecurityContextHolderFilter
 * para que se ejecuten los @PreAuthorize.
 */
class DashboardControllerTest {

    private GetCustomerOverview overview;
    private GetHistory history;
    private GetHistoryDiff historyDiff;
    private SearchCustomers searchCustomers;
    private GetOpenAlerts openAlerts;
    private AcknowledgeAlert acknowledge;
    private AccessScope scope;
    private MockMvc mvc;

    private static final Instant AT = Instant.parse("2026-09-18T10:00:00Z");
    private static final CustomerSnapshot SNAP = new CustomerSnapshot(
            "C-1", "CUST-001", "Acme", CustomerSnapshot.Status.ACTIVE,
            new com.poc.sap.dashboard.customer.domain.AddressData(
                    "Calle 1", "Madrid", "28001", "ES", "M"),
            new com.poc.sap.dashboard.customer.domain.FiscalData("B12345678", null, "Acme", "ES"),
            new com.poc.sap.dashboard.customer.domain.ContactData("info@acme.com", "+34 600 000 000", null, null),
            new com.poc.sap.dashboard.customer.domain.BankingData("ES7621000418401234567890", "BBVAESMM", List.of()));

    private static final String PII_DETAIL = "IBAN rejected: ES7621000418401234567890, email carlos@example.com";

    @BeforeEach
    void setUp() {
        overview = mock(GetCustomerOverview.class);
        history = mock(GetHistory.class);
        historyDiff = mock(GetHistoryDiff.class);
        searchCustomers = mock(SearchCustomers.class);
        openAlerts = mock(GetOpenAlerts.class);
        acknowledge = mock(AcknowledgeAlert.class);
        scope = mock(AccessScope.class);
        mvc = MockMvcBuilders.standaloneSetup(new DashboardController(
                        overview, history, historyDiff, searchCustomers, openAlerts, scope))
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/views/", ".html"))
                .build();
    }

    private static Map<String, FeatureState> features() {
        Map<String, FeatureState> m = new LinkedHashMap<>();
        m.put(CustomerFeature.BANKING.name(),
                new FeatureState(CustomerFeature.BANKING.name(), SyncState.SAP_ERROR,
                        "h-2", "cyc-7", PII_DETAIL, AT));
        return m;
    }

    @Test
    void entityPageRendersForSapRead() throws Exception {
        when(scope.canSeeSensitiveData()).thenReturn(true);
        when(overview.stateOf("C-1")).thenReturn(Optional.of(new CustomerState(
                "C-1",
                new FeatureState(null, SyncState.SENT_SAP, "h-2", "cyc-7", null, AT),
                features(),
                new CycleTrace("cyc-7", "h-2", AT, AT,
                        List.of(new CycleTrace.Step("C-1:BANKING", SyncState.SAP_ERROR, PII_DETAIL, AT))))));
        when(overview.snapshot("C-1")).thenReturn(Optional.of(SNAP));

        mvc.perform(get("/customers/C-1").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + com.poc.sap.common.security.ApiRoles.READ))))
                .andExpect(status().isOk())
                .andExpect(view().name("customer"))
                .andExpect(model().attributeExists("state"))
                .andExpect(model().attributeExists("snapshot"))
                .andExpect(model().attribute("currentPath", "/customers/C-1"));
    }

    @Test
    void externalReadGetsPiiMaskedInModel() throws Exception {
        when(scope.canSeeSensitiveData()).thenReturn(false);
        when(overview.stateOf("C-1")).thenReturn(Optional.of(new CustomerState(
                "C-1",
                new FeatureState(null, SyncState.SENT_SAP, "h-2", "cyc-7", null, AT),
                features(),
                new CycleTrace("cyc-7", "h-2", AT, AT,
                        List.of(new CycleTrace.Step("C-1:BANKING", SyncState.SAP_ERROR, PII_DETAIL, AT))))));
        when(overview.snapshot("C-1")).thenReturn(Optional.of(SNAP));

        mvc.perform(get("/customers/C-1").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + com.poc.sap.common.security.ApiRoles.EXTERNAL_READ))))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("state"))
                .andExpect(model().attribute("piiMasked", true));
    }

    @Test
    void missingEntityReturns404() throws Exception {
        when(scope.canSeeSensitiveData()).thenReturn(true);
        when(overview.stateOf("missing")).thenReturn(Optional.empty());

        mvc.perform(get("/customers/missing").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + com.poc.sap.common.security.ApiRoles.READ))))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyPageRendersVersionsNewestFirst() throws Exception {
        when(scope.canSeeSensitiveData()).thenReturn(true);
        when(history.of("C-1")).thenReturn(List.of(
                new CustomerHistoryReader.HistoryVersion("C-1", "h-2", AT, SNAP)));

        mvc.perform(get("/customers/C-1/history").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + com.poc.sap.common.security.ApiRoles.READ))))
                .andExpect(status().isOk())
                .andExpect(view().name("history"))
                .andExpect(model().attributeExists("versions"));
    }

    @Test
    void searchPageDelegates() throws Exception {
        when(scope.canSeeSensitiveData()).thenReturn(true);
        when(searchCustomers.byTaxId("B12345678")).thenReturn(List.of(SNAP));

        mvc.perform(get("/customers/search").param("q", "B12345678")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + com.poc.sap.common.security.ApiRoles.READ))))
                .andExpect(status().isOk())
                .andExpect(view().name("search"))
                .andExpect(model().attribute("query", "B12345678"));
    }
}
