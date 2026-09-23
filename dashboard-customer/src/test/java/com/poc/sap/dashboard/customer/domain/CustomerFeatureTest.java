package com.poc.sap.dashboard.customer.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enum local con las features del dashboard-customer (no se comparte con
 * customer.domain.CustomerFeature por el aislamiento entre bounded contexts,
 * DashboardIsolationTest). Si el customer-app anade una quinta feature, el
 * dashboard sigue mostrando solo las 4 que sabe pintar.
 */
class CustomerFeatureTest {

    @Test
    void exposesExactlyTheFourKnownFeatures() {
        assertThat(CustomerFeature.values())
                .extracting(Enum::name)
                .containsExactly("ADDRESS", "FISCAL", "CONTACT", "BANKING");
    }
}
