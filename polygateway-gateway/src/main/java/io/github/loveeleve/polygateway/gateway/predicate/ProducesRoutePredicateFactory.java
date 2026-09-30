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

import org.springframework.cloud.gateway.handler.predicate.AbstractRoutePredicateFactory;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The {@code Produces} route predicate: the route matches when one of the configured media types
 * is compatible with the {@code Accept} header of the request (an empty {@code Accept} header
 * accepts everything).
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   predicates:
 *     - Produces=application/json
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class ProducesRoutePredicateFactory extends AbstractRoutePredicateFactory<ProducesRoutePredicateFactory.Config> {

    public ProducesRoutePredicateFactory() {
        super(Config.class);
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("mediaTypes");
    }

    @Override
    public Predicate<ServerWebExchange> apply(Config config) {
        List<MediaType> mediaTypes = ConsumesRoutePredicateFactory.parseMediaTypes(config.getMediaTypes());
        return exchange -> {
            List<MediaType> acceptedMediaTypes = exchange.getRequest().getHeaders().getAccept();
            if (acceptedMediaTypes.isEmpty()) {
                return true;
            }
            return mediaTypes.stream().anyMatch(produces -> acceptedMediaTypes.stream()
                    .anyMatch(accept -> accept.isCompatibleWith(produces)));
        };
    }

    /**
     * The configuration of the {@code Produces} predicate.
     */
    public static class Config {

        private List<String> mediaTypes = new ArrayList<>();

        public List<String> getMediaTypes() {
            return mediaTypes;
        }

        public void setMediaTypes(List<String> mediaTypes) {
            this.mediaTypes = mediaTypes;
        }
    }
}
