package com.poc.sap.dashboard.customer.adapters.persistence;

import com.poc.sap.dashboard.customer.domain.AddressData;
import org.bson.Document;

public final class MongoCustomerAddressMapper {
    private MongoCustomerAddressMapper() {}

    public static AddressData fromBson(Document d) {
        if (d == null) return null;
        return new AddressData(
                d.getString("street"),
                d.getString("city"),
                d.getString("postalCode"),
                d.getString("country"),
                d.getString("region"));
    }
}
