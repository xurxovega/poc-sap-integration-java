package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.customer.adapters.sap.dto.BtpAddressDto;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.port.AddressSapPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador SAP BTP para la feature ADDRESS (SPEC.md §5, TECH.md §8).
 * Mapea AddressData al contrato BTP via {@link BtpAddressDto} y delega en
 * {@link SapClient} de common.
 * Excluyente con el adaptador OData equivalente (sap.odata.address.enabled).
 */
@Component
@ConditionalOnProperty(name = "sap.odata.address.enabled", havingValue = "false", matchIfMissing = true)
public class BtpAddressAdapter implements AddressSapPort {

    private final SapClient sapClient;
    private final String path;

    public BtpAddressAdapter(SapClient sapClient,
                             @Value("${sap.customer.btp.address-path:/sap/btp/odata/CustomerAddress}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, AddressData a) {
        String body = a == null ? "{}" : SapJsonMapper.write(BtpAddressDto.from(entityId, a));
        return sapClient.send(SapDestination.BTP, path, entityId, payloadHash, body);
    }
}