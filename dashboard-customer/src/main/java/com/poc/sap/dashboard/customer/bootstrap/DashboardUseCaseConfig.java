package com.poc.sap.dashboard.customer.bootstrap;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.mongodb.client.MongoClient;
import com.poc.sap.dashboard.customer.adapters.persistence.MongoAlertRepository;
import com.poc.sap.dashboard.customer.adapters.persistence.MongoCustomerImageReader;
import com.poc.sap.dashboard.customer.adapters.persistence.MongoCustomerSearcher;
import com.poc.sap.dashboard.customer.adapters.persistence.MongoCustomerStateReader;
import com.poc.sap.dashboard.customer.adapters.search.ElasticsearchCustomerHistoryReader;
import com.poc.sap.dashboard.customer.application.AcknowledgeAlert;
import com.poc.sap.dashboard.customer.application.GetCustomerOverview;
import com.poc.sap.dashboard.customer.application.GetHistory;
import com.poc.sap.dashboard.customer.application.GetHistoryDiff;
import com.poc.sap.dashboard.customer.application.GetOpenAlerts;
import com.poc.sap.dashboard.customer.application.SearchCustomers;
import com.poc.sap.dashboard.customer.bootstrap.observability.KpiJob;
import com.poc.sap.dashboard.customer.domain.port.AlertRepository;
import com.poc.sap.dashboard.customer.domain.port.CustomerHistoryReader;
import com.poc.sap.dashboard.customer.domain.port.CustomerImageReader;
import com.poc.sap.dashboard.customer.domain.port.CustomerSearcher;
import com.poc.sap.dashboard.customer.domain.port.CustomerStateReader;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Wiring de los use cases y adaptadores (UI-001 H-3). Los use cases del modulo
 * son clases puras: este es el UNICO sitio donde el modulo toca Spring.
 */
@Configuration
public class DashboardUseCaseConfig {

    @Bean Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MongoCustomerImageReader mongoCustomerImageReader(MongoClient client,
                                                       @Value("${dashboard.mongo.database}") String db,
                                                       @Value("${dashboard.mongo.customer-collection}") String coll) {
        return new MongoCustomerImageReader(client, db, coll);
    }

    @Bean
    MongoCustomerStateReader mongoCustomerStateReader(MongoClient client,
                                                       @Value("${dashboard.mongo.database}") String db,
                                                       @Value("${dashboard.mongo.sync-state-collection}") String coll) {
        return new MongoCustomerStateReader(client, db, coll);
    }

    @Bean
    MongoCustomerSearcher mongoCustomerSearcher(MongoClient client,
                                                 @Value("${dashboard.mongo.database}") String db,
                                                 @Value("${dashboard.mongo.customer-collection}") String coll) {
        return new MongoCustomerSearcher(client, db, coll);
    }

    @Bean
    MongoAlertRepository mongoAlertRepository(MongoClient client,
                                                @Value("${dashboard.mongo.database}") String db,
                                                @Value("${dashboard.mongo.alerts-collection}") String coll) {
        return new MongoAlertRepository(client, db, coll);
    }

    @Bean
    ElasticsearchCustomerHistoryReader elasticsearchCustomerHistoryReader(ElasticsearchClient client,
                                                                          @Value("${dashboard.elasticsearch.history-index}") String index) {
        return new ElasticsearchCustomerHistoryReader(client, index);
    }

    @Bean CustomerImageReader customerImageReader(MongoCustomerImageReader r) { return r; }
    @Bean CustomerStateReader customerStateReader(MongoCustomerStateReader r) { return r; }
    @Bean CustomerSearcher customerSearcher(MongoCustomerSearcher r) { return r; }
    @Bean AlertRepository alertRepository(MongoAlertRepository r) { return r; }
    @Bean CustomerHistoryReader customerHistoryReader(ElasticsearchCustomerHistoryReader r) { return r; }

    @Bean GetCustomerOverview getCustomerOverview(CustomerImageReader images, CustomerStateReader states) {
        return new GetCustomerOverview(images, states);
    }
    @Bean GetHistory getHistory(CustomerHistoryReader history) {
        return new GetHistory(history);
    }
    @Bean GetHistoryDiff getHistoryDiff(CustomerHistoryReader history) {
        return new GetHistoryDiff(history);
    }
    @Bean SearchCustomers searchCustomers(CustomerSearcher searcher) {
        return new SearchCustomers(searcher);
    }
    @Bean AcknowledgeAlert acknowledgeAlert(AlertRepository repo, Clock clock) {
        return new AcknowledgeAlert(repo, clock);
    }
    @Bean GetOpenAlerts getOpenAlerts(AlertRepository repo) {
        return new GetOpenAlerts(repo);
    }

    /**
     * Job de KPIs (UI-001 H-5 F-12). El scheduler ya esta activado via
     * {@link com.poc.sap.dashboard.customer.DashboardCustomerApplication#DashboardCustomerApplication()}.
     */
    @Bean
    KpiJob kpiJob(MeterRegistry registry, MongoClient client,
                   @Value("${dashboard.mongo.database:customer}") String database,
                   @Value("${dashboard.kpi.window-days:7}") long windowDays) {
        return new KpiJob(registry, () -> client.getDatabase(database), Duration.ofDays(windowDays));
    }
}
