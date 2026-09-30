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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebEndpointMapping} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class WebEndpointMappingTest {

    @Test
    void testBuilderDefaults() {
        WebEndpointMapping mapping = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns("/orders/{id}")
                .build();

        assertEquals(WebEndpointMapping.Kind.MVC, mapping.getKind());
        assertEquals(0, mapping.getMethods().length);
        assertEquals(0, mapping.getParams().length);
        assertEquals(0, mapping.getHeaders().length);
        assertEquals(0, mapping.getConsumes().length);
        assertEquals(0, mapping.getProduces().length);
        assertTrue(mapping.getId() != 0);
    }

    @Test
    void testDefensiveCopies() {
        String[] patterns = {"/orders/{id}"};
        WebEndpointMapping mapping = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns(patterns)
                .methods("GET")
                .build();

        patterns[0] = "/changed";
        assertArrayEquals(new String[]{"/orders/{id}"}, mapping.getPatterns());

        String[] returned = mapping.getPatterns();
        returned[0] = "/changed";
        assertArrayEquals(new String[]{"/orders/{id}"}, mapping.getPatterns());
    }

    @Test
    void testStableIdAndEquality() {
        WebEndpointMapping first = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns("/orders/{id}").methods("GET").build();
        WebEndpointMapping same = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns("/orders/{id}").methods("GET").build();
        WebEndpointMapping different = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns("/orders/{id}").methods("POST").build();

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertEquals(first.getId(), same.getId());
        assertNotEquals(first, different);

        // The explicit id does not participate in the equality, the contract must hold anyway
        WebEndpointMapping withExplicitId = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .id(12345)
                .patterns("/orders/{id}").methods("GET").build();
        assertEquals(first, withExplicitId);
        assertEquals(first.hashCode(), withExplicitId.hashCode());
    }
}
