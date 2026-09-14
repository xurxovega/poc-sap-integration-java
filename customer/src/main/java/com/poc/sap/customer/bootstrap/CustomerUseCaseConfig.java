package com.poc.sap.customer.bootstrap;

import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SyncNotificationPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
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
import com.poc.sap.customer.application.general.DeleteCustomerUseCase;
import com.poc.sap.customer.application.general.SyncCustomerUseCase;
import com.poc.sap.customer.application.general.ValidateCustomerUseCase;
import com.poc.sap.customer.domain.port.AddressSapPort;
import com.poc.sap.customer.domain.port.BankingSapPort;
import com.poc.sap.customer.domain.port.ContactSapPort;
import com.poc.sap.customer.domain.port.CustomerHistoryIndexerPort;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;
import com.poc.sap.customer.domain.port.CustomerSapOutboundPort;
import com.poc.sap.customer.domain.port.FiscalSapPort;
import com.poc.sap.customer.domain.port.MandateSapOutboundPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring de los use cases del dominio customer. Es el UNICO sitio donde
 * {@code application} toca Spring: las clases de use case no llevan
 * {@code @Service} ni dependen de Micrometer (auditoria A4; plan Fase 7).
 * {@code ApplicationPurityTest} (ArchUnit) lo vigila.
 */
@Configuration
public class CustomerUseCaseConfig {

    @Bean SyncAddressUseCase syncAddressUseCase(AddressSapPort p, SyncStateRepositoryPort s, MetricsPort m) { return new SyncAddressUseCase(p, s, m); }
    @Bean ValidateAddressUseCase validateAddressUseCase(SyncStateRepositoryPort s, MetricsPort m) { return new ValidateAddressUseCase(s, m); }
    @Bean SyncFiscalUseCase syncFiscalUseCase(FiscalSapPort p, SyncStateRepositoryPort s, MetricsPort m) { return new SyncFiscalUseCase(p, s, m); }
    @Bean ValidateFiscalUseCase validateFiscalUseCase(SyncStateRepositoryPort s, MetricsPort m) { return new ValidateFiscalUseCase(s, m); }
    @Bean SyncContactUseCase syncContactUseCase(ContactSapPort p, SyncStateRepositoryPort s, MetricsPort m) { return new SyncContactUseCase(p, s, m); }
    @Bean ValidateContactUseCase validateContactUseCase(SyncStateRepositoryPort s, MetricsPort m) { return new ValidateContactUseCase(s, m); }
    @Bean SyncBankingUseCase syncBankingUseCase(BankingSapPort p, SyncStateRepositoryPort s, MetricsPort m) { return new SyncBankingUseCase(p, s, m); }
    @Bean ValidateBankingUseCase validateBankingUseCase(SyncStateRepositoryPort s, MetricsPort m) { return new ValidateBankingUseCase(s, m); }
    @Bean DeleteMandateUseCase deleteMandateUseCase(MandateSapOutboundPort p, SyncStateRepositoryPort s, MetricsPort m) { return new DeleteMandateUseCase(p, s, m); }

    @Bean
    SyncCustomerUseCase syncCustomerUseCase(CustomerLegacyRepositoryPort legacy, CustomerImageStorePort image,
                                            CustomerHistoryIndexerPort history, SyncStateRepositoryPort state, MetricsPort metrics,
                                            SyncNotificationPort notifications, SyncAddressUseCase address, SyncFiscalUseCase fiscal,
                                            SyncContactUseCase contact, SyncBankingUseCase banking) {
        return new SyncCustomerUseCase(legacy, image, history, state, metrics, notifications, address, fiscal, contact, banking);
    }

    @Bean
    ValidateCustomerUseCase validateCustomerUseCase(CustomerLegacyRepositoryPort legacy, SyncStateRepositoryPort state, MetricsPort metrics) {
        return new ValidateCustomerUseCase(legacy, state, metrics);
    }

    @Bean
    DeleteCustomerUseCase deleteCustomerUseCase(CustomerImageStorePort image, CustomerSapOutboundPort sap,
                                                SyncStateRepositoryPort state, MetricsPort metrics) {
        return new DeleteCustomerUseCase(image, sap, state, metrics);
    }

    @Bean
    CustomerStateUseCase customerStateUseCase(SyncStateRepositoryPort state) {
        return new CustomerStateUseCase(state);
    }

    @Bean
    CustomerHistoryUseCase customerHistoryUseCase(CustomerHistoryIndexerPort history) {
        return new CustomerHistoryUseCase(history);
    }
}
