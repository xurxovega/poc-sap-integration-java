package com.poc.sap.customer.bootstrap;

import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SyncNotificationPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.customer.application.address.SyncAddressUseCase;
import com.poc.sap.customer.application.address.ValidateAddressUseCase;
import com.poc.sap.customer.application.banking.DeleteMandateUseCase;
import com.poc.sap.customer.application.banking.SyncBankingUseCase;
import com.poc.sap.customer.application.banking.ValidateBankingUseCase;
import com.poc.sap.customer.application.contact.SyncContactUseCase;
import com.poc.sap.customer.application.contact.ValidateContactUseCase;
import com.poc.sap.customer.application.fiscal.SyncFiscalUseCase;
import com.poc.sap.customer.application.fiscal.ValidateFiscalUseCase;
import com.poc.sap.customer.application.general.CustomerHistoryUseCase;
import com.poc.sap.customer.application.general.CustomerStateUseCase;
import com.poc.sap.customer.application.general.LookupBusinessPartnerUseCase;
import com.poc.sap.customer.application.general.DeleteCustomerUseCase;
import com.poc.sap.customer.application.general.SyncCustomerUseCase;
import com.poc.sap.customer.application.general.ValidateCustomerUseCase;
import com.poc.sap.customer.domain.port.AddressSapPort;
import com.poc.sap.customer.domain.port.BankingSapPort;
import com.poc.sap.customer.domain.port.BusinessPartnerReadPort;
import com.poc.sap.customer.domain.port.ContactSapPort;
import com.poc.sap.customer.domain.port.CustomerHistoryIndexerPort;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;
import com.poc.sap.customer.domain.port.CustomerSapOutboundPort;
import com.poc.sap.customer.domain.port.FiscalSapPort;
import com.poc.sap.customer.domain.port.MandateSapOutboundPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wiring de los use cases del dominio customer. Es el UNICO sitio donde
 * {@code application} toca Spring: las clases de use case no llevan
 * {@code @Service} ni dependen de Micrometer (auditoria A4; plan Fase 7).
 * {@code ApplicationPurityTest} (ArchUnit) lo vigila.
 */
@Configuration
public class CustomerUseCaseConfig {

    /**
     * Reloj unico del dominio. Los use cases no llaman a {@code Instant.now()}:
     * el instante de cada paso de la traza tiene que ser verificable en un test
     * (anexo 04, lista "O"). Es {@code @Bean} porque {@code application} no puede
     * depender de Spring (AGENTS.md §1.5).
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean SyncAddressUseCase syncAddressUseCase(AddressSapPort p, SyncStateRepositoryPort s, MetricsPort m, Clock c, SapUpsertSettings u) { return new SyncAddressUseCase(p, s, m, c, u); }
    @Bean ValidateAddressUseCase validateAddressUseCase(SyncStateRepositoryPort s, MetricsPort m, Clock c) { return new ValidateAddressUseCase(s, m, c); }
    @Bean SyncFiscalUseCase syncFiscalUseCase(FiscalSapPort p, SyncStateRepositoryPort s, MetricsPort m, Clock c, SapUpsertSettings u) { return new SyncFiscalUseCase(p, s, m, c, u); }
    @Bean ValidateFiscalUseCase validateFiscalUseCase(SyncStateRepositoryPort s, MetricsPort m, Clock c) { return new ValidateFiscalUseCase(s, m, c); }
    @Bean SyncContactUseCase syncContactUseCase(ContactSapPort p, SyncStateRepositoryPort s, MetricsPort m, Clock c, SapUpsertSettings u) { return new SyncContactUseCase(p, s, m, c, u); }
    @Bean ValidateContactUseCase validateContactUseCase(SyncStateRepositoryPort s, MetricsPort m, Clock c) { return new ValidateContactUseCase(s, m, c); }
    @Bean SyncBankingUseCase syncBankingUseCase(BankingSapPort p, SyncStateRepositoryPort s, MetricsPort m, Clock c, SapUpsertSettings u) { return new SyncBankingUseCase(p, s, m, c, u); }
    @Bean ValidateBankingUseCase validateBankingUseCase(SyncStateRepositoryPort s, MetricsPort m, Clock c) { return new ValidateBankingUseCase(s, m, c); }
    @Bean DeleteMandateUseCase deleteMandateUseCase(MandateSapOutboundPort p, SyncStateRepositoryPort s, MetricsPort m, Clock c) { return new DeleteMandateUseCase(p, s, m, c); }

    @Bean
    SyncCustomerUseCase syncCustomerUseCase(CustomerLegacyRepositoryPort legacy, CustomerImageStorePort image,
                                            CustomerHistoryIndexerPort history, SyncStateRepositoryPort state, MetricsPort metrics,
                                            SyncNotificationPort notifications, SyncAddressUseCase address, SyncFiscalUseCase fiscal,
                                            SyncContactUseCase contact, SyncBankingUseCase banking, Clock clock,
                                            @Value("${sap.partial-failure.rethrow-when-nothing-reached-sap:true}") boolean rethrow) {
        return new SyncCustomerUseCase(legacy, image, history, state, metrics, notifications,
                address, fiscal, contact, banking, clock, rethrow);
    }

    @Bean
    ValidateCustomerUseCase validateCustomerUseCase(CustomerLegacyRepositoryPort legacy, SyncStateRepositoryPort state,
                                                    MetricsPort metrics, Clock clock) {
        return new ValidateCustomerUseCase(legacy, state, metrics, clock);
    }

    @Bean
    DeleteCustomerUseCase deleteCustomerUseCase(CustomerImageStorePort image, CustomerSapOutboundPort sap,
                                                SyncStateRepositoryPort state, MetricsPort metrics, Clock clock) {
        return new DeleteCustomerUseCase(image, sap, state, metrics, clock);
    }

    @Bean
    CustomerStateUseCase customerStateUseCase(SyncStateRepositoryPort state) {
        return new CustomerStateUseCase(state);
    }

    @Bean
    CustomerHistoryUseCase customerHistoryUseCase(CustomerHistoryIndexerPort history) {
        return new CustomerHistoryUseCase(history);
    }

    /**
     * Consulta puntual de Business Partners ya creados en SAP (PRD-9). Solo
     * se monta si {@link BusinessPartnerReadPort} esta activo (lo decide
     * {@code BusinessPartnerReadEnabled} a partir de
     * {@code sap.odata.read.enabled} o {@code sap.odata.customer.enabled}); si
     * no lo esta, el controller no se inyecta y Spring no registra los
     * endpoints (R-5 del spec).
     *
     * <p>{@code @ConditionalOnBean} evita que este bean intente resolverse
     * cuando el puerto todavia no existe (es condicional a su vez). Mismo
     * patron que {@code CommonApplication} sigue con el resto de piezas
     * opcionales del puerto.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(BusinessPartnerReadPort.class)
    LookupBusinessPartnerUseCase lookupBusinessPartnerUseCase(BusinessPartnerReadPort port) {
        return new LookupBusinessPartnerUseCase(port);
    }
}
