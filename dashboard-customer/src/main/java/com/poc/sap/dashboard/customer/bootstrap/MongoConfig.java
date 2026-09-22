package com.poc.sap.dashboard.customer.bootstrap;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean del {@link MongoClient} nativo del modulo (UI-001 H-3). El dashboard
 * lee Mongo del dominio customer con el driver sync, NO con Spring Data, para
 * que el aislamiento con el bounded context quede visible en codigo
 * (DashboardIsolationTest).
 */
@Configuration
public class MongoConfig {

    /**
     * URI por defecto cae al localhost que sirve {@code external-services} en
     * dev. En cluster va via {@code MONGO_URL_CUSTOMER}.
     */
    @Bean(destroyMethod = "close")
    public MongoClient mongoClient(@Value("${spring.mongodb.uri}") String uri) {
        return MongoClients.create(uri);
    }
}
