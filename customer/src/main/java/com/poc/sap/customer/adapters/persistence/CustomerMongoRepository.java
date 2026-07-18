package com.poc.sap.customer.adapters.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CustomerMongoRepository extends MongoRepository<CustomerDocument, String> {
}
