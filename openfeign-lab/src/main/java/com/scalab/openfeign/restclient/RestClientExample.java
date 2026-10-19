package com.scalab.openfeign.restclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Component
public class RestClientExample {

    private static final Logger log = LoggerFactory.getLogger(RestClientExample.class);

    private final RestClient restClient;

    public RestClientExample() {
        this.restClient = RestClient.builder()
                .baseUrl("http://localhost:28001")
                .build();
    }

    public String health() {
        long start = System.nanoTime();
        String result = restClient.get()
                .uri("/health")
                .retrieve()
                .body(String.class);
        log.debug("[RestClient] GET /health cost={}ns response={}", System.nanoTime() - start, result);
        return result;
    }

    public Map<String, Object> discoveryStatus() {
        long start = System.nanoTime();
        Map<String, Object> result = restClient.get()
                .uri("/discovery/status")
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        log.debug("[RestClient] GET /discovery/status cost={}ns", System.nanoTime() - start);
        return result;
    }

    public Map<String, Object> compareToFeign(String feignResultV1, Map<String, Object> feignResultV2) {
        Map<String, Object> comparison = new HashMap<>();
        comparison.put("note", "RestClient vs OpenFeign 代码对比");

        Map<String, Object> v1 = new HashMap<>();
        v1.put("feignResult", feignResultV1);
        v1.put("restClientResult", health());
        v1.put("same", feignResultV1 != null && feignResultV1.equals(health()));
        comparison.put("v1", v1);

        Map<String, Object> v2 = new HashMap<>();
        v2.put("feignResult", feignResultV2);
        v2.put("restClientResult", discoveryStatus());
        v2.put("same", feignResultV2 != null && feignResultV2.equals(discoveryStatus()));
        comparison.put("v2", v2);

        return comparison;
    }
}
