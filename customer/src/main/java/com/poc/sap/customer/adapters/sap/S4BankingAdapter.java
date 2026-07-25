package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.customer.adapters.sap.dto.S4BankingDto;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.port.BankingSapPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador SAP S/4 nativo para la feature BANKING (incluye mandates).
 * Reemplaza al antiguo S4MandateAdapter.
 * Excluyente con el adaptador OData equivalente (sap.odata.banking.enabled).
 */
@Component
@ConditionalOnProperty(name = "sap.odata.banking.enabled", havingValue = "false", matchIfMissing = true)
public class S4BankingAdapter implements BankingSapPort {

    private final SapClient sapClient;
    private final String path;

    public S4BankingAdapter(SapClient sapClient,
                            @Value("${sap.mandate.s4.path:/sap/opu/odata/sap/API_CUSTOMER_MANDATE}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, BankingData b) {
        String body = b == null ? "{}" : SapJsonMapper.write(S4BankingDto.from(entityId, b));
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }
}
