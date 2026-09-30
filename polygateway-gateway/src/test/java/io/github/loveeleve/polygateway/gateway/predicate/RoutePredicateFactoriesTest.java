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
package io.github.loveeleve.polygateway.gateway.predicate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConsumesRoutePredicateFactory} / {@link ProducesRoutePredicateFactory} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class RoutePredicateFactoriesTest {

    @Test
    void testConsumes() {
        ConsumesRoutePredicateFactory factory = new ConsumesRoutePredicateFactory();
        ConsumesRoutePredicateFactory.Config config = new ConsumesRoutePredicateFactory.Config();
        config.setMediaTypes(List.of("application/json"));
        Predicate<ServerWebExchange> predicate = factory.apply(config);

        assertTrue(predicate.test(exchangeWithContentType(MediaType.APPLICATION_JSON)));
        // A compatible media type with parameters matches
        assertTrue(predicate.test(exchangeWithContentType(MediaType.parseMediaType("application/json;charset=UTF-8"))));
        assertFalse(predicate.test(exchangeWithContentType(MediaType.TEXT_PLAIN)));
        assertFalse(predicate.test(exchangeWithContentType(null)));
        assertEquals(List.of("mediaTypes"), factory.shortcutFieldOrder());
    }

    @Test
    void testProduces() {
        ProducesRoutePredicateFactory factory = new ProducesRoutePredicateFactory();
        ProducesRoutePredicateFactory.Config config = new ProducesRoutePredicateFactory.Config();
        config.setMediaTypes(List.of("application/json"));
        Predicate<ServerWebExchange> predicate = factory.apply(config);

        assertTrue(predicate.test(exchangeWithAccept(MediaType.APPLICATION_JSON)));
        assertTrue(predicate.test(exchangeWithAccept(MediaType.ALL)));
        assertFalse(predicate.test(exchangeWithAccept(MediaType.TEXT_PLAIN)));
        // No Accept header accepts anything
        assertTrue(predicate.test(exchangeWithAccept(null)));
    }

    private static ServerWebExchange exchangeWithContentType(MediaType contentType) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/orders");
        if (contentType != null) {
            builder.header(HttpHeaders.CONTENT_TYPE, contentType.toString());
        }
        return MockServerWebExchange.from(builder.build());
    }

    private static ServerWebExchange exchangeWithAccept(MediaType accept) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/orders");
        if (accept != null) {
            builder.header(HttpHeaders.ACCEPT, accept.toString());
        }
        return MockServerWebExchange.from(builder.build());
    }
}
