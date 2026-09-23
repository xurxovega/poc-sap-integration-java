package com.poc.sap.customer.adapters.index;

import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerHistoryIndexerPort;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Adaptador Elasticsearch del historico de Customer (TECH.md §7).
 * Implementa {@link CustomerHistoryIndexerPort}.
 */
@Component
public class ElasticsearchCustomerIndexer implements CustomerHistoryIndexerPort {

    private final CustomerHistoryRepository repo;

    public ElasticsearchCustomerIndexer(CustomerHistoryRepository repo) {
        this.repo = repo;
    }

    @Override
    public void index(String entityId, Customer entity, String payloadHash) {
        CustomerHistoryDoc doc = CustomerHistoryDoc.from(entity, payloadHash, Instant.now());
        repo.save(doc);
    }

    @Override
    public List<Customer> history(String entityId) {
        return repo.findByCustomerIdOrderByTimestampDesc(entityId).stream()
                .map(CustomerHistoryDoc::toDomain)
                .toList();
    }

    @Override
    public List<Snapshot<Customer>> snapshots(String entityId) {
        return repo.findByCustomerIdOrderByTimestampDesc(entityId).stream()
                .map(d -> new Snapshot<>(d.getPayloadHash(), d.getTimestamp(), d.toDomain()))
                .toList();
    }
}
