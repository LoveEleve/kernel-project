/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.loveeleve.polygateway.gateway;

import io.github.loveeleve.polygateway.PolyGatewayConstants;
import io.github.loveeleve.polygateway.web.WebEndpointExclusion;
import io.github.loveeleve.polygateway.web.WebEndpointMapping;
import io.github.loveeleve.polygateway.web.WebEndpointMappingCodec;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebEndpointRouteDefinitionLocator} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class WebEndpointRouteDefinitionLocatorTest {

    @Test
    void testGenerateRoutes() {
        WebEndpointMapping orders = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .id(1).patterns("/orders/{id}").methods("GET").build();
        WebEndpointMapping special = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .id(2).patterns("/orders/special").methods("GET").build();
        WebEndpointMapping reconcile = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .id(3).patterns("/internal/reconcile").methods("POST")
                .headers("X-Api-Version=2", "X-Version=1.0", "X-Legacy!=old")
                .params("debug=true", "!trace")
                .produces("application/json").build();

        StubDiscoveryClient discoveryClient = new StubDiscoveryClient();
        discoveryClient.addInstance(instance("order-service", "/api", orders, special, reconcile));
        WebEndpointRouteDefinitionLocator locator = new WebEndpointRouteDefinitionLocator(discoveryClient,
                new PolyGatewayGatewayProperties());

        List<RouteDefinition> routes = locator.loadRouteDefinitions();
        assertEquals(3, routes.size());

        RouteDefinition ordersRoute = route(routes, "/orders/{id}");
        assertEquals("polygateway:order-service:1", ordersRoute.getId());
        assertEquals("lb://order-service", ordersRoute.getUri().toString());
        assertTrue(predicateArg(ordersRoute, "Path", "/order-service/api/orders/{id}"));
        assertTrue(predicateArg(ordersRoute, "Method", "GET"));
        assertTrue(ordersRoute.getFilters().stream().anyMatch(filter -> "StripPrefix".equals(filter.getName())
                && filter.getArgs().containsValue("1")));

        // The more specific route must be evaluated first
        RouteDefinition specialRoute = route(routes, "/orders/special");
        assertTrue(specialRoute.getOrder() < ordersRoute.getOrder());

        // The header / query / produces conditions become the corresponding predicates
        RouteDefinition reconcileRoute = route(routes, "/internal/reconcile");
        assertTrue(predicateArg(reconcileRoute, "Header", "X-Api-Version"));
        assertTrue(predicateArg(reconcileRoute, "Header", "2"));
        assertTrue(predicateArg(reconcileRoute, "Query", "debug"));
        assertTrue(predicateArg(reconcileRoute, "Query", "true"));
        assertTrue(predicateArg(reconcileRoute, "Produces", "application/json"));

        // The literal value is quoted so that the regexp semantics can not over-match
        assertTrue(predicateArg(reconcileRoute, "Header", Pattern.quote("1.0")));
        // The negated conditions are skipped
        assertFalse(predicateArg(reconcileRoute, "Header", "X-Legacy"));
        assertFalse(predicateArg(reconcileRoute, "Query", "trace"));
    }

    @Test
    void testMultiplePatternsAndContextPathNormalization() {
        WebEndpointMapping endpoint = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .id(1).patterns("/orders/{id}", "/v2/orders/{id}").methods("GET").build();
        StubDiscoveryClient discoveryClient = new StubDiscoveryClient();
        // A context path without the leading slash, with a trailing slash
        discoveryClient.addInstance(instance("order-service", "api/", endpoint));

        RouteDefinition route = new WebEndpointRouteDefinitionLocator(discoveryClient,
                new PolyGatewayGatewayProperties()).loadRouteDefinitions().get(0);
        assertTrue(predicateArg(route, "Path", "/order-service/api/orders/{id}"));
        assertTrue(predicateArg(route, "Path", "/order-service/api/v2/orders/{id}"));
    }

    @Test
    void testExcludedEndpointGetsNoRoute() {
        WebEndpointMapping internal = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .id(1).patterns("/internal/reconcile").methods("POST").build();
        StubDiscoveryClient discoveryClient = new StubDiscoveryClient();
        discoveryClient.addInstance(instance("order-service", null, internal));

        PolyGatewayGatewayProperties properties = new PolyGatewayGatewayProperties();
        WebEndpointExclusion exclusion = new WebEndpointExclusion();
        exclusion.setPatterns("/internal/**");
        properties.getExcludes().add(exclusion);

        WebEndpointRouteDefinitionLocator locator = new WebEndpointRouteDefinitionLocator(discoveryClient, properties);
        assertTrue(locator.loadRouteDefinitions().isEmpty());
    }

    @Test
    void testEndpointsAreMergedAcrossInstances() {
        WebEndpointMapping first = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .id(1).patterns("/orders/{id}").methods("GET").build();
        WebEndpointMapping canary = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .id(2).patterns("/canary/orders").methods("GET").build();

        StubDiscoveryClient discoveryClient = new StubDiscoveryClient();
        discoveryClient.addInstance(instance("order-service", null, first));
        discoveryClient.addInstance(instance("order-service", null, canary));

        WebEndpointRouteDefinitionLocator locator = new WebEndpointRouteDefinitionLocator(discoveryClient,
                new PolyGatewayGatewayProperties());
        assertEquals(2, locator.loadRouteDefinitions().size());
    }

    @Test
    void testNegatedConditionIsSkipped() {
        WebEndpointMapping endpoint = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .id(1).patterns("/orders").methods("GET").headers("!X-Internal").build();
        StubDiscoveryClient discoveryClient = new StubDiscoveryClient();
        discoveryClient.addInstance(instance("order-service", null, endpoint));

        RouteDefinition route = new WebEndpointRouteDefinitionLocator(discoveryClient,
                new PolyGatewayGatewayProperties()).loadRouteDefinitions().get(0);
        assertFalse(route.getPredicates().stream()
                .anyMatch(predicate -> "Header".equals(predicate.getName())));
    }

    private static ServiceInstance instance(String serviceId, String contextPath, WebEndpointMapping... endpoints) {
        DefaultServiceInstance instance = new DefaultServiceInstance(serviceId + "-1", serviceId,
                "127.0.0.1", 8080, false);
        Map<String, String> metadata = instance.getMetadata();
        metadata.put(PolyGatewayConstants.WEB_ENDPOINTS_METADATA_NAME,
                WebEndpointMappingCodec.encode(List.of(endpoints)));
        if (contextPath != null) {
            metadata.put(PolyGatewayConstants.CONTEXT_PATH_METADATA_NAME, contextPath);
        }
        return instance;
    }

    private static RouteDefinition route(List<RouteDefinition> routes, String path) {
        return routes.stream()
                .filter(route -> predicateArg(route, "Path", path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No route was generated for the path " + path));
    }

    private static boolean predicateArg(RouteDefinition route, String predicateName, String valuePart) {
        for (PredicateDefinition predicate : route.getPredicates()) {
            if (predicateName.equals(predicate.getName())
                    && predicate.getArgs().values().stream().anyMatch(value -> value.contains(valuePart))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The stub discovery client of the tests.
     */
    static class StubDiscoveryClient implements DiscoveryClient {

        private final Map<String, List<ServiceInstance>> instances = new LinkedHashMap<>();

        void addInstance(ServiceInstance instance) {
            instances.computeIfAbsent(instance.getServiceId(), key -> new ArrayList<>()).add(instance);
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public List<ServiceInstance> getInstances(String serviceId) {
            return instances.getOrDefault(serviceId, List.of());
        }

        @Override
        public List<String> getServices() {
            return new ArrayList<>(instances.keySet());
        }
    }
}
