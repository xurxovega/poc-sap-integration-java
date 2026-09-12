package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.customer.adapters.sap.dto.BtpBankingDto;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.port.BankingSapPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador SAP BTP para la feature BANKING (sdd/customer/sincronizacion-datos-bancarios.md).
 * Misma familia y destino que el resto de {@code Btp*Adapter}. Antes se llamaba
 * {@code S4BankingAdapter} y enviaba a {@code API_CUSTOMER_MANDATE}, una API que
 * no existe en S/4 (auditoria B3).
 * Excluyente con el adaptador OData equivalente (sap.odata.banking.enabled).
 */
@Component
@ConditionalOnProperty(name = "sap.odata.banking.enabled", havingValue = "false", matchIfMissing = true)
public class BtpBankingAdapter implements BankingSapPort {

    private final SapClient sapClient;
    private final String path;

    public BtpBankingAdapter(SapClient sapClient,
                             @Value("${sap.customer.btp.banking-path:/sap/btp/odata/CustomerBanking}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, BankingData b) {
        String body = b == null ? "{}" : SapJsonMapper.write(BtpBankingDto.from(entityId, b));
        return sapClient.send(SapDestination.BTP, path, entityId, payloadHash, body);
    }
}
