package com.poc.sap.dashboard.customer.adapters.persistence;

import com.poc.sap.dashboard.customer.domain.FiscalData;
import org.bson.Document;

public final class MongoCustomerFiscalMapper {
    private MongoCustomerFiscalMapper() {}

    public static FiscalData fromBson(Document d) {
        if (d == null) return null;
        return new FiscalData(
                d.getString("taxId"),
                d.getString("vatNumber"),
                d.getString("legalName"),
                d.getString("taxResidency"));
    }
}
