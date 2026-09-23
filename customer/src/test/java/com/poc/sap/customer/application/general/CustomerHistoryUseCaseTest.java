package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.port.HistoryIndexerPort.Snapshot;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerHistoryIndexerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerHistoryUseCaseTest {

    @Mock CustomerHistoryIndexerPort historyIndexer;

    private CustomerHistoryUseCase useCase;

    private final Instant t1 = Instant.parse("2026-07-01T00:00:00Z");
    private final Instant t2 = Instant.parse("2026-07-02T00:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new CustomerHistoryUseCase(historyIndexer);
    }

    private Snapshot<Customer> version(String hash, Instant ts, String name) {
        Customer base = CustomerFixtures.validCustomer();
        Customer c = new Customer(base.id(), base.code(), name, base.status(),
                base.address(), base.fiscal(), base.contact(), base.banking());
        return new Snapshot<>(hash, ts, c);
    }

    @Test
    void historyDelegatesToIndexer() {
        List<Snapshot<Customer>> versions = List.of(version("h-2", t2, "ACME"));
        when(historyIndexer.snapshots("C-1")).thenReturn(versions);

        assertThat(useCase.history("C-1")).isEqualTo(versions);
    }

    @Test
    void diffWithoutParamsComparesLatestAgainstPrevious() {
        when(historyIndexer.snapshots("C-1")).thenReturn(List.of(
                version("h-2", t2, "ACME NUEVA"),
                version("h-1", t1, "ACME")));

        var diff = useCase.diff("C-1", null, null);

        assertThat(diff.from().payloadHash()).isEqualTo("h-1");
        assertThat(diff.to().payloadHash()).isEqualTo("h-2");
        assertThat(diff.changes()).containsOnlyKeys("name");
        assertThat(diff.changes().get("name").before()).isEqualTo("ACME");
        assertThat(diff.changes().get("name").after()).isEqualTo("ACME NUEVA");
    }

    @Test
    void diffByExplicitHashes() {
        when(historyIndexer.snapshots("C-1")).thenReturn(List.of(
                version("h-3", t2, "V3"),
                version("h-2", t2, "V2"),
                version("h-1", t1, "V1")));

        var diff = useCase.diff("C-1", "h-1", "h-3");

        assertThat(diff.from().payloadHash()).isEqualTo("h-1");
        assertThat(diff.to().payloadHash()).isEqualTo("h-3");
        assertThat(diff.changes().get("name")).isNotNull();
    }

    @Test
    void diffWithoutHistoryThrowsNoSuchElement() {
        when(historyIndexer.snapshots("C-1")).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.diff("C-1", null, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Sin historico");
    }

    @Test
    void diffWithSingleVersionThrowsNoPrevious() {
        when(historyIndexer.snapshots("C-1")).thenReturn(List.of(version("h-1", t1, "ACME")));

        assertThatThrownBy(() -> useCase.diff("C-1", null, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("No hay version anterior");
    }

    @Test
    void diffWithUnknownHashThrows() {
        when(historyIndexer.snapshots("C-1")).thenReturn(List.of(
                version("h-2", t2, "V2"),
                version("h-1", t1, "V1")));

        assertThatThrownBy(() -> useCase.diff("C-1", "h-99", null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("h-99");
    }
}
