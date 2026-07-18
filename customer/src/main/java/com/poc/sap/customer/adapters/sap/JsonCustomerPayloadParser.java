package com.poc.sap.customer.adapters.sap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import com.poc.sap.customer.domain.port.CustomerPayloadParserPort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Parsea el payload JSON del mensaje de ingestion a Customer (TECH.md §4).
 * El payload puede incluir parcialmente las features; las ausentes se rellenan
 * con null/empty segun corresponda. El parser es tolerante con nuevas features.
 */
@Component
public class JsonCustomerPayloadParser implements CustomerPayloadParserPort {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Customer parse(IngestionMessage message) {
        try {
            var node = mapper.readTree(message.payload());
            var addr = node.path("address");
            var fis = node.path("fiscal");
            var con = node.path("contact");
            var ban = node.path("banking");

            AddressData address = new AddressData(
                    addr.path("street").asText(null),
                    addr.path("city").asText(null),
                    addr.path("postalCode").asText(null),
                    addr.path("country").asText("ES"),
                    addr.path("region").asText(null));

            FiscalData fiscal = new FiscalData(
                    fis.path("taxId").asText(null),
                    fis.path("vatNumber").asText(null),
                    fis.path("legalName").asText(null),
                    fis.path("taxResidency").asText("ES"));

            ContactData contact = new ContactData(
                    con.path("email").asText(null),
                    con.path("phone").asText(null),
                    con.path("fax").asText(null),
                    con.path("website").asText(null));

            List<String> mandateIds = List.of();
            if (ban.has("mandateIds")) {
                mandateIds = mapper.convertValue(ban.path("mandateIds"),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            }
            BankingData banking = new BankingData(
                    ban.path("iban").asText(null),
                    ban.path("bic").asText(null),
                    mandateIds);

            return new Customer(
                    message.entityId(),
                    node.path("code").asText(),
                    node.path("name").asText(null),
                    Customer.Status.valueOf(node.path("status").asText("ACTIVE")),
                    address, fiscal, contact, banking);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload de Customer invalido: " + e.getMessage(), e);
        }
    }
}