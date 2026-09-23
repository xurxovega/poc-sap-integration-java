package com.poc.sap.dashboard.customer.adapters.persistence;

import com.poc.sap.dashboard.customer.domain.ContactData;
import org.bson.Document;

public final class MongoCustomerContactMapper {
    private MongoCustomerContactMapper() {}

    public static ContactData fromBson(Document d) {
        if (d == null) return null;
        return new ContactData(
                d.getString("email"),
                d.getString("phone"),
                d.getString("fax"),
                d.getString("website"));
    }
}
