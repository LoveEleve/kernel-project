package com.scalab.es.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES 客户端装配——官方 Java Client 8.12.2（xhs-project 同款）。
 *
 * <p>注意：Spring Boot 3.2 不自动装配新版 elasticsearch-java client（只认旧版 RestHighLevelClient），
 * 需手动装配：RestClient（低层 HTTP）→ RestClientTransport（传输层）→ ElasticsearchClient（API 层）。
 */
@Configuration
public class EsClientConfig {

    @Value("${elasticsearch.uris}")
    private String uris;

    @Bean(destroyMethod = "close")
    public RestClient restClient() {
        return RestClient.builder(HttpHost.create(uris)).build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        return new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }
}
