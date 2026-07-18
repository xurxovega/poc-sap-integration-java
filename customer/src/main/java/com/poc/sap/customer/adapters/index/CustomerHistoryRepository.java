package com.poc.sap.customer.adapters.index;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface CustomerHistoryRepository
        extends ElasticsearchRepository<CustomerHistoryDoc, String> {

    List<CustomerHistoryDoc> findByCustomerIdOrderByTimestampDesc(String customerId);
}
