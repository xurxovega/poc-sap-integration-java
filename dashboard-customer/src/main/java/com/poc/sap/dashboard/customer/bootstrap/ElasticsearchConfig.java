package com.poc.sap.dashboard.customer.bootstrap;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean del {@link ElasticsearchClient} nativo (UI-001 H-3). Sin Spring Data
 * Elasticsearch: codigo explicito sobre el SDK oficial de ES 8.x.
 */
@Configuration
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public ElasticsearchClient elasticsearchClient(
            @Value("${spring.elasticsearch.uris:http://localhost:9200}") String uri) {
        // Soporta varias URIs separadas por coma (Spring ya splitea). Aqui nos
        // quedamos con la primera porque el caso de uso es un cluster local.
        String first = uri.split(",")[0].trim();
        java.net.URI parsed = java.net.URI.create(first);
        HttpHost host = new HttpHost(parsed.getHost(), parsed.getPort(),
                parsed.getScheme() == null ? "http" : parsed.getScheme());
        RestClient restClient = RestClient.builder(host).build();
        ElasticsearchTransport transport =
                new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
