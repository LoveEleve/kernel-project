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
package io.github.loveeleve.polygateway.provider;

import io.github.loveeleve.polygateway.web.WebEndpointMapping;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects the {@link WebEndpointMapping}s of a Spring MVC application: every endpoint registered
 * in the {@link RequestMappingHandlerMapping} of the application is converted into the six
 * dimensional {@link WebEndpointMapping} model.
 *
 * <p>The endpoints of the Spring Boot internal controllers (e.g. the actuator endpoints and the
 * error controller) are skipped by {@link #isIncludedController(String)}, so the infrastructure
 * endpoints are never published to the gateway.
 *
 * <p>The collected mappings are sorted deterministically and an explicit id is assigned by the
 * position, so that the same endpoint set always produces the same ids (and therefore the same
 * route identifiers on the gateway).
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class WebEndpointMappingsCollector {

    /**
     * Collects the web endpoints of the given application context.
     *
     * @param context the application context
     * @return the collected mappings, never {@code null}
     */
    public List<WebEndpointMapping> collect(ApplicationContext context) {
        List<WebEndpointMapping> mappings = new ArrayList<>();
        for (RequestMappingHandlerMapping handlerMapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            collect(handlerMapping, mappings);
        }
        mappings.sort(mappingComparator());
        assignIds(mappings);
        return mappings;
    }

    private void collect(RequestMappingHandlerMapping handlerMapping, List<WebEndpointMapping> mappings) {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            if (!isIncludedController(handlerMethod.getBeanType().getName())) {
                continue;
            }
            mappings.add(toMapping(entry.getKey()));
        }
    }

    private WebEndpointMapping toMapping(RequestMappingInfo requestMappingInfo) {
        WebEndpointMapping.Builder builder = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns(patterns(requestMappingInfo))
                .methods(methods(requestMappingInfo))
                .params(requestMappingInfo.getParamsCondition().getExpressions().stream()
                        .map(Object::toString).toArray(String[]::new))
                .headers(requestMappingInfo.getHeadersCondition().getExpressions().stream()
                        .map(Object::toString).toArray(String[]::new))
                .consumes(requestMappingInfo.getConsumesCondition().getConsumableMediaTypes().stream()
                        .map(Object::toString).toArray(String[]::new))
                .produces(requestMappingInfo.getProducesCondition().getProducibleMediaTypes().stream()
                        .map(Object::toString).toArray(String[]::new));
        return builder.build();
    }

    private String[] patterns(RequestMappingInfo requestMappingInfo) {
        Set<String> patterns = new LinkedHashSet<>();
        PathPatternsRequestCondition pathPatternsCondition = requestMappingInfo.getPathPatternsCondition();
        if (pathPatternsCondition != null) {
            for (PathPattern pattern : pathPatternsCondition.getPatterns()) {
                patterns.add(pattern.getPatternString());
            }
        } else {
            PatternsRequestCondition patternsCondition = requestMappingInfo.getPatternsCondition();
            if (patternsCondition != null) {
                patterns.addAll(patternsCondition.getPatterns());
            }
        }
        return patterns.toArray(new String[0]);
    }

    private String[] methods(RequestMappingInfo requestMappingInfo) {
        Set<RequestMethod> methods = requestMappingInfo.getMethodsCondition().getMethods();
        return methods.stream().map(Enum::name).toArray(String[]::new);
    }

    /**
     * Tests whether the controller of the given class name is published to the gateway. The
     * infrastructure controllers of Spring Boot (the actuator endpoints, the error controller, ...)
     * are skipped.
     *
     * @param controllerClassName the class name of the controller
     * @return {@code true} when the endpoints of the controller are collected
     */
    protected boolean isIncludedController(String controllerClassName) {
        return !controllerClassName.startsWith("org.springframework.boot.");
    }

    /**
     * @return the comparator ordering the mappings by their six dimensional conditions
     */
    protected Comparator<WebEndpointMapping> mappingComparator() {
        return Comparator
                .comparing((WebEndpointMapping mapping) -> String.join(",", mapping.getPatterns()))
                .thenComparing(mapping -> String.join(",", mapping.getMethods()))
                .thenComparing(mapping -> String.join(",", mapping.getConsumes()))
                .thenComparing(mapping -> String.join(",", mapping.getProduces()))
                .thenComparing(mapping -> String.join(",", mapping.getParams()))
                .thenComparing(mapping -> String.join(",", mapping.getHeaders()));
    }

    private void assignIds(List<WebEndpointMapping> mappings) {
        List<WebEndpointMapping> identified = new ArrayList<>(mappings.size());
        for (int i = 0; i < mappings.size(); i++) {
            WebEndpointMapping mapping = mappings.get(i);
            identified.add(WebEndpointMapping.builder(mapping.getKind())
                    .id(i + 1)
                    .negated(mapping.isNegated())
                    .patterns(mapping.getPatterns())
                    .methods(mapping.getMethods())
                    .params(mapping.getParams())
                    .headers(mapping.getHeaders())
                    .consumes(mapping.getConsumes())
                    .produces(mapping.getProduces())
                    .build());
        }
        mappings.clear();
        mappings.addAll(identified);
    }
}
