package com.poc.sap.customer.adapters.persistence;

import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Adaptador MongoDB de la imagen actual de Customer (TECH.md §7).
 * Implementa {@link CustomerImageStorePort}.
 */
@Repository
public class MongoCustomerImageStore implements CustomerImageStorePort {

    private final CustomerMongoRepository mongo;

    public MongoCustomerImageStore(CustomerMongoRepository mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(String entityId, Customer entity) {
        mongo.save(CustomerDocument.fromDomain(entity));
    }

    @Override
    public Optional<Customer> find(String entityId) {
        return mongo.findById(entityId).map(CustomerDocument::toDomain);
    }

    @Override
    public void delete(String entityId) {
        mongo.deleteById(entityId);
    }
}
