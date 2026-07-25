package com.poc.sap.article.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.web.server.servlet.context.ServletComponentScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Entry point del dominio ARTICLE.
 *
 * <p>Los repositorios Spring Data se declaran explicitamente: la deteccion
 * automatica solo escanea el paquete de esta clase (bootstrap), no los
 * adapters ni el shared kernel common.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.sap.cloud.sdk", "com.poc.sap.article", "com.poc.sap.common"})
@ServletComponentScan(basePackages = {"com.sap.cloud.sdk", "com.poc.sap.article", "com.poc.sap.common"})
@EnableJpaRepositories(basePackages = "com.poc.sap.article.adapters.persistence")
@EntityScan(basePackages = "com.poc.sap.article.adapters.persistence")
@EnableMongoRepositories(basePackages = {
        "com.poc.sap.article.adapters.persistence",
        "com.poc.sap.common.adapters.persistence"})
@EnableElasticsearchRepositories(basePackages = "com.poc.sap.article.adapters.index")
public class ArticleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArticleApplication.class, args);
    }
}
