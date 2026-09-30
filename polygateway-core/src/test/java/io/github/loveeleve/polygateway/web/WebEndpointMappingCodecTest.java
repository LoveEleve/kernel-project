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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebEndpointMappingCodec} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class WebEndpointMappingCodecTest {

    @Test
    void testRoundTrip() {
        WebEndpointMapping mapping = WebEndpointMapping.builder(WebEndpointMapping.Kind.MVC)
                .patterns("/orders/{id}", "/v1/orders/{id}")
                .methods("GET", "HEAD")
                .params("locale=zh")
                .headers("X-Api-Version=2")
                .consumes("application/json")
                .produces("application/json")
                .build();

        List<WebEndpointMapping> decoded = WebEndpointMappingCodec.decode(WebEndpointMappingCodec.encode(List.of(mapping)));
        assertEquals(1, decoded.size());
        WebEndpointMapping result = decoded.get(0);
        assertEquals(mapping, result);
        assertEquals(mapping.getId(), result.getId());
        assertArrayEquals(new String[]{"/orders/{id}", "/v1/orders/{id}"}, result.getPatterns());
        assertArrayEquals(new String[]{"GET", "HEAD"}, result.getMethods());
        assertArrayEquals(new String[]{"locale=zh"}, result.getParams());
        assertArrayEquals(new String[]{"X-Api-Version=2"}, result.getHeaders());
        assertArrayEquals(new String[]{"application/json"}, result.getConsumes());
        assertArrayEquals(new String[]{"application/json"}, result.getProduces());
    }

    @Test
    void testRoundTripWithEmptyDims() {
        WebEndpointMapping mapping = WebEndpointMapping.builder(WebEndpointMapping.Kind.WEB_FLUX)
                .patterns("/ping")
                .build();

        List<WebEndpointMapping> decoded = WebEndpointMappingCodec.decode(WebEndpointMappingCodec.encode(List.of(mapping)));
        assertEquals(mapping, decoded.get(0));
    }

    @Test
    void testEmptyList() {
        String json = WebEndpointMappingCodec.encode(List.of());
        assertEquals("[]", json);
        assertTrue(WebEndpointMappingCodec.decode(json).isEmpty());
    }

    @Test
    void testInvalidJson() {
        assertThrows(RuntimeException.class, () -> WebEndpointMappingCodec.decode("{}"));
    }
}
