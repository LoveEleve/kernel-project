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
package io.github.loveeleve.polygateway.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebEndpointExclusion} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class WebEndpointExclusionTest {

    @Test
    void testPatternMatching() {
        WebEndpointExclusion exclusion = exclusion("/internal/**");

        assertTrue(exclusion.matches(endpoint("/internal/reconcile")));
        assertTrue(exclusion.matches(endpoint("/internal/orders/{id}")));
        assertFalse(exclusion.matches(endpoint("/orders/{id}")));
        assertFalse(exclusion.matches(endpoint("/internalized/reconcile")));
    }

    @Test
    void testNoPatternNeverMatches() {
        WebEndpointExclusion exclusion = new WebEndpointExclusion();
        exclusion.setMethods("GET");

        assertFalse(exclusion.matches(endpoint("/orders")));
    }

    @Test
    void testMethods() {
        WebEndpointExclusion exclusion = exclusion("/internal/**");
        exclusion.setMethods("POST");

        assertTrue(exclusion.matches(endpoint("/internal/reconcile", "POST")));
        assertFalse(exclusion.matches(endpoint("/internal/reconcile", "GET")));
        // The endpoint declares no method condition, it accepts any method
        assertTrue(exclusion.matches(endpoint("/internal/reconcile")));
    }

    @Test
    void testParamsAndHeaders() {
        WebEndpointExclusion exclusion = exclusion("/internal/**");
        exclusion.setParams("debug=true");
        exclusion.setHeaders("X-Internal=true");

        WebEndpointMapping matching = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns("/internal/reconcile")
                .params("debug=true")
                .headers("X-Internal=true")
                .build();
        WebEndpointMapping missingParam = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns("/internal/reconcile")
                .headers("X-Internal=true")
                .build();
        WebEndpointMapping missingHeader = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns("/internal/reconcile")
                .params("debug=true")
                .build();

        assertTrue(exclusion.matches(matching));
        assertFalse(exclusion.matches(missingParam));
        assertFalse(exclusion.matches(missingHeader));
    }

    @Test
    void testConsumesAndProduces() {
        WebEndpointExclusion exclusion = exclusion("/internal/**");
        exclusion.setConsumes("application/json");
        exclusion.setProduces("application/json");

        assertTrue(exclusion.matches(endpointWithMediaTypes("application/json", "application/json")));
        assertFalse(exclusion.matches(endpointWithMediaTypes("text/plain", "application/json")));
        assertFalse(exclusion.matches(endpointWithMediaTypes("application/json", "text/plain")));
        // The endpoint declares no media type condition
        assertFalse(exclusion.matches(endpoint("/internal/reconcile", "POST")));
    }

    @Test
    void testEveryDimensionMustMatch() {
        WebEndpointExclusion exclusion = exclusion("/internal/**");
        exclusion.setMethods("POST");
        exclusion.setConsumes("application/json");

        assertTrue(exclusion.matches(endpointWithMediaTypes("application/json", null, "POST")));
        // The method does not match
        assertFalse(exclusion.matches(endpointWithMediaTypes("application/json", null, "GET")));
        // The media type does not match
        assertFalse(exclusion.matches(endpointWithMediaTypes("text/plain", null, "POST")));
    }

    private static WebEndpointExclusion exclusion(String pattern) {
        WebEndpointExclusion exclusion = new WebEndpointExclusion();
        exclusion.setPatterns(pattern);
        return exclusion;
    }

    private static WebEndpointMapping endpoint(String pattern, String... methods) {
        return WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns(pattern)
                .methods(methods)
                .build();
    }

    private static WebEndpointMapping endpointWithMediaTypes(String consumes, String produces, String... methods) {
        WebEndpointMapping.Builder builder = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns("/internal/reconcile")
                .methods(methods);
        if (consumes != null) {
            builder.consumes(consumes);
        }
        if (produces != null) {
            builder.produces(produces);
        }
        return builder.build();
    }
}
