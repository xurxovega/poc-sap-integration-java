package com.poc.sap.dashboard.customer.adapters.persistence;

import com.poc.sap.dashboard.customer.domain.BankingData;
import org.bson.Document;

import java.util.List;

public final class MongoCustomerBankingMapper {
    private MongoCustomerBankingMapper() {}

    @SuppressWarnings("unchecked")
    public static BankingData fromBson(Document d) {
        if (d == null) return null;
        List<String> mandates = d.getList("mandateIds", String.class, List.of());
        return new BankingData(
                d.getString("iban"),
                d.getString("bic"),
                mandates == null ? List.of() : mandates);
    }
}
