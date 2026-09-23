package com.poc.sap.dashboard.customer.bootstrap.web;

import com.poc.sap.common.security.AccessScope;
import com.poc.sap.common.security.ApiRoles;
import com.poc.sap.dashboard.customer.application.AcknowledgeAlert;
import com.poc.sap.dashboard.customer.application.GetOpenAlerts;
import com.poc.sap.dashboard.customer.domain.Alert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice web del {@link AlertController} (UI-001 H-4 F-9 AC-6): POST
 * /customers/alerts/{id}/ack construye el actor {@code <rol>:<subject>}
 * del {@link Authentication} y delega en {@link AcknowledgeAlert}.
 *
 * <p>MockMvc standalone no evalua @PreAuthorize (eso vive en el filtro de
 * seguridad, no se enchufa en standalone); estos tests verifican la logica
 * del controller y dejan la matriz de autorizacion a {@code EndpointsDeclareAccessTest}.
 */
class AlertControllerTest {

    private AcknowledgeAlert acknowledge;
    private GetOpenAlerts openAlerts;
    private AccessScope scope;
    private MockMvc mvc;

    private static final Instant AT = Instant.parse("2026-09-18T10:00:00Z");

    @BeforeEach
    void setUp() {
        acknowledge = mock(AcknowledgeAlert.class);
        openAlerts = mock(GetOpenAlerts.class);
        scope = mock(AccessScope.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new AlertController(acknowledge, openAlerts, scope))
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/views/", ".html"))
                .build();
    }

    private static Authentication auth(String name, String... roles) {
        return new StubAuthentication(name, roles);
    }

    @Test
    void ackPersistsAckedByUsingRoleAndSubject() throws Exception {
        Alert acked = new Alert("a-1", "C-1", "FAILURE", "kafka", "x", AT, AT.plusSeconds(30), "sap-write:ana");
        when(acknowledge.acknowledge(eq("a-1"), eq("sap-write:ana"))).thenReturn(acked);
        when(scope.canSeeSensitiveData()).thenReturn(true);

        mvc.perform(post("/customers/alerts/{id}/ack", "a-1")
                        .principal(() -> "ana")
                        .with(request -> {
                            request.setUserPrincipal(auth("ana",
                                    "ROLE_" + ApiRoles.WRITE));
                            return request;
                        }))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/customers/alerts/open"));

        verify(acknowledge).acknowledge("a-1", "sap-write:ana");
    }

    @Test
    void ackUsesTheHighestRoleAvailable() throws Exception {
        // Si el token lleva ADMIN + WRITE (jerarquia), cogemos ADMIN.
        when(acknowledge.acknowledge(eq("a-1"), eq("sap-admin:ana"))).thenReturn(
                new Alert("a-1", "C-1", "FAILURE", "kafka", "x", AT, AT, "sap-admin:ana"));

        mvc.perform(post("/customers/alerts/{id}/ack", "a-1")
                        .with(request -> {
                            request.setUserPrincipal(auth("ana",
                                    "ROLE_" + ApiRoles.WRITE,
                                    "ROLE_" + ApiRoles.ADMIN));
                            return request;
                        }))
                .andExpect(status().is3xxRedirection());

        verify(acknowledge).acknowledge("a-1", "sap-admin:ana");
    }

    @Test
    void getOpenAlertsDelegates() throws Exception {
        List<Alert> alerts = List.of(
                new Alert("a-1", "C-1", "FAILURE", "kafka", "IBAN invalido", AT, null, null));
        when(openAlerts.all()).thenReturn(alerts);
        when(scope.canSeeSensitiveData()).thenReturn(true);

        mvc.perform(get("/customers/alerts/open"))
                .andExpect(status().isOk())
                .andExpect(view().name("alerts"))
                .andExpect(model().attributeExists("alerts"));
    }

    /** Stub de Authentication para no acoplar el test al stack OAuth2. */
    record StubAuthentication(String name, java.util.Collection<? extends SimpleGrantedAuthority> authorities)
            implements Authentication {
        @Override public java.util.Collection<? extends SimpleGrantedAuthority> getAuthorities() { return authorities; }
        @Override public Object getCredentials() { return null; }
        @Override public Object getDetails() { return null; }
        @Override public String getName() { return name; }
        @Override public boolean isAuthenticated() { return true; }
        @Override public void setAuthenticated(boolean isAuthenticated) { }
        @Override public java.security.Principal getPrincipal() { return () -> name; }

        StubAuthentication(String name, String... roles) {
            this(name, java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
        }
    }
}
