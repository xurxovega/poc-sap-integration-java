package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.customer.adapters.sap.dto.BtpCustomerDto;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerSapOutboundPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adaptador SAP BTP para el aggregate Customer (operaciones generales como
 * DELETE OData). Las features parciales (address/fiscal/contact/banking) tienen
 * sus propios adapters.
 */
@Component
public class BtpCustomerAdapter implements CustomerSapOutboundPort {

    private final SapClient sapClient;
    private final String path;

    public BtpCustomerAdapter(SapClient sapClient,
                              @Value("${sap.customer.btp.path:/sap/btp/odata/Customer}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, Customer customer) {
        String body = customer == null ? "{}" : SapJsonMapper.write(BtpCustomerDto.from(customer));
        return sapClient.send(SapDestination.BTP, path, entityId, payloadHash, body);
    }
}
