package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.customer.domain.port.BusinessPartnerReadPort;
import com.poc.sap.integration.api.customer.model.ABusinessPartnerType;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador de lectura OData para la API Business Partner vía modelos generados.
 */
@Component
@ConditionalOnProperty(name = "sap.odata.read.enabled", havingValue = "true")
public class BusinessPartnerReadAdapter implements BusinessPartnerReadPort {

    private final SapClient sapClient;
    private final String path;

    public BusinessPartnerReadAdapter(
            SapClient sapClient,
            @Value("${sap.odata.bp-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public Optional<BusinessPartnerSummary> findById(String businessPartnerCode) {
        SapResponse r = sapClient.get(SapDestination.S4_NATIVE,
                path + "('" + businessPartnerCode + "')");
        if (!r.isSuccess()) return Optional.empty();
        return parseSingle(r.body());
    }

    @Override
    public List<BusinessPartnerSummary> searchByCategory(String category, int top) {
        return search("BusinessPartnerCategory eq '" + category + "'", top);
    }

    @Override
    public List<BusinessPartnerSummary> findCustomers(int top) {
        return searchByCategory("2", top);
    }

    @Override
    public List<BusinessPartnerSummary> findSuppliers(int top) {
        return searchByCategory("1", top);
    }

    private List<BusinessPartnerSummary> search(String filter, int top) {
        SapResponse r = sapClient.get(SapDestination.S4_NATIVE,
                path + "?$top=" + top + "&$filter=" + filter);
        if (!r.isSuccess()) return Collections.emptyList();
        return parseList(r.body());
    }

    /**
     * Parsea un único BP usando el modelo generado {@link ABusinessPartnerType}.
     * La respuesta OData v2 viene envuelta en {@code {"d": {...}}}.
     */
    private Optional<BusinessPartnerSummary> parseSingle(String body) {
        try {
            JsonNode root = SapJsonMapper.mapper().readTree(body);
            JsonNode d = root.has("d") ? root.get("d") : root;
            ABusinessPartnerType bp = SapJsonMapper.mapper().treeToValue(d, ABusinessPartnerType.class);
            return Optional.of(toSummary(bp));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private List<BusinessPartnerSummary> parseList(String body) {
        try {
            JsonNode root = SapJsonMapper.mapper().readTree(body);
            JsonNode d = root.has("d") ? root.get("d") : root;
            JsonNode results = d.path("results");
            if (!results.isArray()) return Collections.emptyList();

            List<BusinessPartnerSummary> list = new ArrayList<>();
            for (JsonNode node : results) {
                ABusinessPartnerType bp = SapJsonMapper.mapper().treeToValue(node, ABusinessPartnerType.class);
                list.add(toSummary(bp));
            }
            return list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private BusinessPartnerSummary toSummary(ABusinessPartnerType bp) {
        var d = bp.getD();   // OData v2 response wrapper: {"d": {...}}
        return new BusinessPartnerSummary(
                n(d != null ? d.getBusinessPartner() : null),
                n(d != null ? d.getBusinessPartnerFullName() : null),
                n(d != null ? d.getBusinessPartnerCategory() : null));
    }

    private static String n(String s) { return s == null ? "" : s; }
}
