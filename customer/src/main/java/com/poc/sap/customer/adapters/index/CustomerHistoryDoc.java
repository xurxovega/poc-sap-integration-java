package com.poc.sap.customer.adapters.index;

import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

// createIndex=false: el indice no se crea en el arranque (permite bootear sin ES;
// ES lo crea en la primera escritura o lo gestiona operaciones con template propio)
@Document(indexName = "customers_history", createIndex = false)
public class CustomerHistoryDoc {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String customerId;

    @Field(type = FieldType.Keyword)
    private String code;

    @Field(type = FieldType.Text)
    private String name;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Object)
    private AddressData address;

    @Field(type = FieldType.Object)
    private FiscalData fiscal;

    @Field(type = FieldType.Object)
    private ContactData contact;

    @Field(type = FieldType.Object)
    private BankingData banking;

    @Field(type = FieldType.Keyword)
    private String payloadHash;

    @Field(type = FieldType.Date, name = "@timestamp")
    private Instant timestamp;

    public static CustomerHistoryDoc from(Customer c, String payloadHash, Instant ts) {
        CustomerHistoryDoc d = new CustomerHistoryDoc();
        d.id = c.id() + "-" + payloadHash;
        d.customerId = c.id();
        d.code = c.code();
        d.name = c.name();
        d.status = c.status() != null ? c.status().name() : null;
        d.address = c.address();
        d.fiscal = c.fiscal();
        d.contact = c.contact();
        d.banking = c.banking();
        d.payloadHash = payloadHash;
        d.timestamp = ts;
        return d;
    }

    public Customer toDomain() {
        return new Customer(
                customerId, code, name,
                status != null ? Customer.Status.valueOf(status) : Customer.Status.ACTIVE,
                address, fiscal, contact,
                banking != null ? banking : new BankingData(null, null, java.util.List.of()));
    }

    public String getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getPayloadHash() { return payloadHash; }
    public Instant getTimestamp() { return timestamp; }
}