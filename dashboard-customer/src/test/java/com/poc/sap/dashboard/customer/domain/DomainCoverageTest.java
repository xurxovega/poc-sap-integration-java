package com.poc.sap.dashboard.customer.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cubre las invariantes defensivas de los records del dominio (UI-001 H-2)
 * que el resto de tests no ejercitaba pero JaCoCo necesita contar para
 * superar el {@code jacoco.domain.line-minimum=0.75} del reactor.
 */
class DomainCoverageTest {

    @Test
    void fiscalDataIsPlain() {
        FiscalData f = new FiscalData("B12345678", "ESB12345678", "Acme", "ES");
        assertThat(f.taxId()).isEqualTo("B12345678");
        assertThat(f.vatNumber()).isEqualTo("ESB12345678");
        assertThat(f.legalName()).isEqualTo("Acme");
        assertThat(f.taxResidency()).isEqualTo("ES");
    }

    @Test
    void contactDataIsPlain() {
        ContactData c = new ContactData("a@b.es", "+34111", null, "https://a");
        assertThat(c.email()).isEqualTo("a@b.es");
        assertThat(c.phone()).isEqualTo("+34111");
        assertThat(c.fax()).isNull();
        assertThat(c.website()).isEqualTo("https://a");
    }

    @Test
    void bankingDataNormalizesMandateIds() {
        BankingData b1 = new BankingData("ES760001", "BBVAESMM", null);
        assertThat(b1.mandateIds()).isEmpty();

        List<String> list = List.of("m-1");
        BankingData b2 = new BankingData("ES760001", "BBVAESMM", list);
        assertThat(b2.mandateIds()).isSameAs(list);
    }

    @Test
    void alertRejectsBlankAlertIdAndEntityId() {
        assertThatThrownBy(() -> new Alert(null, "C-1", "WARN", "kafka", "x",
                Instant.now(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alertId");
        assertThatThrownBy(() -> new Alert("a-1", null, "WARN", "kafka", "x",
                Instant.now(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId");
    }

    @Test
    void alertIsOpenWhenAckedAtIsNull() {
        Alert open = new Alert("a-1", "C-1", "WARN", "kafka", "x",
                Instant.parse("2026-09-18T10:00:00Z"), null, null);
        assertThat(open.isOpen()).isTrue();

        Alert closed = new Alert("a-1", "C-1", "WARN", "kafka", "x",
                Instant.parse("2026-09-18T10:00:00Z"),
                Instant.parse("2026-09-18T11:00:00Z"), "sap-write:ana");
        assertThat(closed.isOpen()).isFalse();
    }

    @Test
    void customerSnapshotNonNullFeatureKeys() {
        CustomerSnapshot s = new CustomerSnapshot("C-1", "CUST-1", "Acme",
                CustomerSnapshot.Status.ACTIVE,
                null, new FiscalData("x", null, "y", "ES"), new ContactData("z", null, null, null), null);
        assertThat(s.nonNullFeatureKeys()).containsExactly("FISCAL", "CONTACT");
    }
}
