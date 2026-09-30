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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The endpoint level {@link RouteDefinitionLocator}: it discovers the web endpoints published by
 * the provider applications (see the metadata protocol of {@link WebEndpointMappingCodec}) and
 * converts them into the route definitions of Spring Cloud Gateway.
 *
 * <p>For every endpoint one route is generated :
 *
 * <ul>
 * <li>the path is {@code /{serviceId}{contextPath}{endpointPattern}}, so the route of a service
 * never conflicts with the route of another service</li>
 * <li>the URI is {@code lb://serviceId}, so the instance is selected by the official reactive load
 * balancer (non blocking)</li>
 * <li>the six dimensional conditions of the endpoint become the {@code Path}, {@code Method},
 * {@code Header}, {@code Query}, {@code Consumes} and {@code Produces} predicates</li>
 * <li>the single {@code StripPrefix=1} filter strips the {@code /{serviceId}} prefix, the
 * context path stays in the forwarded path</li>
 * <li>the route order prefers the more specific endpoint patterns</li>
 * </ul>
 *
 * <p>The endpoints of all the instances of a service are merged (a union), so an endpoint exposed
 * by only one instance (e.g. during a canary release) is still routed. The endpoints matched by
 * the configured {@link WebEndpointExclusion}s get no route at all.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class WebEndpointRouteDefinitionLocator implements RouteDefinitionLocator {

    private static final Logger logger = LoggerFactory.getLogger(WebEndpointRouteDefinitionLocator.class);

    private static final String PATH_PREDICATE_NAME = "Path";

    private static final String METHOD_PREDICATE_NAME = "Method";

    private static final String HEADER_PREDICATE_NAME = "Header";

    private static final String QUERY_PREDICATE_NAME = "Query";

    private static final String CONSUMES_PREDICATE_NAME = "Consumes";

    private static final String PRODUCES_PREDICATE_NAME = "Produces";

    private static final String STRIP_PREFIX_FILTER = "StripPrefix=1";

    private final DiscoveryClient discoveryClient;

    private final PolyGatewayGatewayProperties properties;

    public WebEndpointRouteDefinitionLocator(DiscoveryClient discoveryClient, PolyGatewayGatewayProperties properties) {
        this.discoveryClient = discoveryClient;
        this.properties = properties;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(loadRouteDefinitions());
    }

    /**
     * Loads the current route definitions from the discovery client.
     *
     * @return the route definitions
     */
    public List<RouteDefinition> loadRouteDefinitions() {
        List<RouteDefinition> routeDefinitions = new ArrayList<>();
        for (String serviceId : discoveryClient.getServices()) {
            routeDefinitions.addAll(loadServiceRoutes(serviceId));
        }
        logger.debug("Loaded {} endpoint route definition(s)", routeDefinitions.size());
        return routeDefinitions;
    }

    /**
     * Loads the route definitions of one service.
     *
     * @param serviceId the service id
     * @return the route definitions
     */
    public List<RouteDefinition> loadServiceRoutes(String serviceId) {
        List<ServiceInstance> serviceInstances = discoveryClient.getInstances(serviceId);
        if (CollectionUtils.isEmpty(serviceInstances)) {
            return List.of();
        }
        Set<WebEndpointMapping> endpoints = new LinkedHashSet<>();
        String contextPath = null;
        for (ServiceInstance serviceInstance : serviceInstances) {
            Map<String, String> metadata = serviceInstance.getMetadata();
            String endpointsJSON = metadata.get(PolyGatewayConstants.WEB_ENDPOINTS_METADATA_NAME);
            if (StringUtils.hasText(endpointsJSON)) {
                try {
                    endpoints.addAll(WebEndpointMappingCodec.decode(endpointsJSON));
                } catch (RuntimeException e) {
                    logger.warn("The web endpoints metadata of the service instance [{}] can't be decoded : {}",
                            serviceInstance.getInstanceId(), e.getMessage());
                }
            }
            if (contextPath == null) {
                contextPath = normalizeContextPath(metadata.get(PolyGatewayConstants.CONTEXT_PATH_METADATA_NAME));
            }
        }
        if (endpoints.isEmpty()) {
            return List.of();
        }
        List<RouteDefinition> routeDefinitions = new ArrayList<>(endpoints.size());
        for (WebEndpointMapping endpoint : endpoints) {
            if (isExcluded(endpoint)) {
                logger.debug("The endpoint [{}] of the service [{}] is excluded from the routes", endpoint, serviceId);
                continue;
            }
            routeDefinitions.add(createRouteDefinition(serviceId, contextPath, endpoint));
        }
        return routeDefinitions;
    }

    /**
     * Creates the route definition of one endpoint.
     *
     * @param serviceId   the service id
     * @param contextPath the context path of the service, {@code null} or empty when none
     * @param endpoint    the endpoint
     * @return the route definition
     */
    protected RouteDefinition createRouteDefinition(String serviceId, String contextPath, WebEndpointMapping endpoint) {
        RouteDefinition routeDefinition = new RouteDefinition();
        routeDefinition.setId(PolyGatewayConstants.ROUTE_ID_PREFIX + serviceId + ":" + endpoint.getId());
        routeDefinition.setUri(URI.create("lb://" + serviceId));
        routeDefinition.setOrder(routeOrder(endpoint));
        routeDefinition.setPredicates(createPredicates(serviceId, contextPath, endpoint));
        routeDefinition.setFilters(List.of(new FilterDefinition(STRIP_PREFIX_FILTER)));
        return routeDefinition;
    }

    private List<PredicateDefinition> createPredicates(String serviceId, String contextPath, WebEndpointMapping endpoint) {
        List<PredicateDefinition> predicates = new ArrayList<>();
        predicates.add(new PredicateDefinition(PATH_PREDICATE_NAME + "=" + String.join(",", paths(serviceId, contextPath, endpoint))));
        if (endpoint.getMethods().length > 0) {
            predicates.add(new PredicateDefinition(METHOD_PREDICATE_NAME + "=" + String.join(",", endpoint.getMethods())));
        }
        for (String header : endpoint.getHeaders()) {
            addConditionPredicate(predicates, HEADER_PREDICATE_NAME, "header", header);
        }
        for (String param : endpoint.getParams()) {
            addConditionPredicate(predicates, QUERY_PREDICATE_NAME, "param", param);
        }
        if (endpoint.getConsumes().length > 0) {
            predicates.add(new PredicateDefinition(CONSUMES_PREDICATE_NAME + "=" + String.join(",", endpoint.getConsumes())));
        }
        if (endpoint.getProduces().length > 0) {
            predicates.add(new PredicateDefinition(PRODUCES_PREDICATE_NAME + "=" + String.join(",", endpoint.getProduces())));
        }
        return predicates;
    }

    /**
     * Adds a {@code Header} / {@code Query} predicate for a request condition of the endpoint. A
     * negated condition (e.g. {@code !debug}) cannot be expressed by the official predicates and is
     * skipped, the endpoint stays reachable.
     */
    private void addConditionPredicate(List<PredicateDefinition> predicates, String predicateName, String argName,
                                       String condition) {
        if (condition.startsWith("!") || condition.startsWith("=") || condition.contains("!=")) {
            logger.debug("The negated condition [{}] can't be expressed by the {} predicate", condition, predicateName);
            return;
        }
        int equalsIndex = condition.indexOf('=');
        String name = equalsIndex < 0 ? condition : condition.substring(0, equalsIndex);
        String value = equalsIndex < 0 ? null : condition.substring(equalsIndex + 1);
        if (!StringUtils.hasText(name)) {
            return;
        }
        PredicateDefinition predicate = new PredicateDefinition();
        predicate.setName(predicateName);
        predicate.addArg(argName, name);
        if (StringUtils.hasText(value)) {
            // The endpoint conditions are literal values, quote them so that the regexp semantics
            // of the official predicate can never over-match (e.g. the dot in "1.0")
            predicate.addArg("regexp", Pattern.quote(value));
        }
        predicates.add(predicate);
    }

    private List<String> paths(String serviceId, String contextPath, WebEndpointMapping endpoint) {
        String prefix = "/" + serviceId + (contextPath == null ? "" : contextPath);
        List<String> paths = new ArrayList<>(endpoint.getPatterns().length);
        for (String pattern : endpoint.getPatterns()) {
            paths.add(prefix + (pattern.startsWith("/") ? pattern : "/" + pattern));
        }
        return paths;
    }

    /**
     * Computes the route order: the more literal segments (and the fewer variables) the endpoint
     * pattern has, the more specific it is, and the more specific routes must be evaluated first.
     *
     * @param endpoint the endpoint
     * @return the route order
     */
    protected int routeOrder(WebEndpointMapping endpoint) {
        int literalTokens = 0;
        int variableTokens = 0;
        int wildcards = 0;
        for (String pattern : endpoint.getPatterns()) {
            for (String token : pattern.split("/")) {
                if (token.isEmpty()) {
                    continue;
                }
                if (token.contains("**")) {
                    wildcards++;
                } else if (token.contains("{")) {
                    variableTokens++;
                } else {
                    literalTokens++;
                }
            }
        }
        return -(literalTokens * 2) - variableTokens + (wildcards * 2);
    }

    private boolean isExcluded(WebEndpointMapping endpoint) {
        List<WebEndpointExclusion> excludes = properties.getExcludes();
        if (excludes == null) {
            return false;
        }
        for (WebEndpointExclusion exclusion : excludes) {
            if (exclusion.matches(endpoint)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeContextPath(String contextPath) {
        if (!StringUtils.hasText(contextPath) || "/".equals(contextPath)) {
            return null;
        }
        String normalized = contextPath.startsWith("/") ? contextPath : "/" + contextPath;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    @Override
    public String toString() {
        return "WebEndpointRouteDefinitionLocator{discoveryClient=" + discoveryClient.description() + '}';
    }
}
