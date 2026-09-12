package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.customer.adapters.sap.dto.BtpCustomerDto;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerSapOutboundPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador SAP BTP para las operaciones generales de Customer (TECH.md §8):
 * alta/actualizacion del agregado y baja (sdd/customer/baja-cliente.md).
 *
 * Activo salvo que {@code sap.odata.customer.enabled=true}, en cuyo caso lo
 * sustituye {@code BusinessPartnerODataAdapter} sobre el mismo puerto.
 */
@Component
@ConditionalOnProperty(name = "sap.odata.customer.enabled", havingValue = "false", matchIfMissing = true)
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
        if (customer == null) {
            // Un Customer nulo ya no es una senal de borrado: la baja tiene su
            // propia operacion (auditoria B2, AC-6 del spec de baja).
            throw new IllegalArgumentException("customer obligatorio para enviar a SAP; para la baja usa delete()");
        }
        String body = SapJsonMapper.write(BtpCustomerDto.from(customer));
        return sapClient.send(SapDestination.BTP, path, entityId, payloadHash, body);
    }

    @Override
    public SapResponse delete(String entityId, String payloadHash) {
        return sapClient.delete(SapDestination.BTP, path + "('" + entityId + "')");
    }
}
