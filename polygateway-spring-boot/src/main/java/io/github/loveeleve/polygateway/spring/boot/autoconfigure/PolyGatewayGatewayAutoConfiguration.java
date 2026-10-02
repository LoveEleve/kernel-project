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
package io.github.loveeleve.polygateway.spring.boot.autoconfigure;

import io.github.loveeleve.polygateway.gateway.PolyGatewayGatewayProperties;
import io.github.loveeleve.polygateway.gateway.PolyGatewayRouteRefreshListener;
import io.github.loveeleve.polygateway.gateway.WebEndpointRouteDefinitionLocator;
import io.github.loveeleve.polygateway.gateway.predicate.ConsumesRoutePredicateFactory;
import io.github.loveeleve.polygateway.gateway.predicate.ProducesRoutePredicateFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * The auto-configuration of the PolyGateway gateway side: it generates the endpoint level routes
 * from the instance metadata of the discovery client, registers the extra route predicate
 * factories and refreshes the routes on the discovery heartbeats.
 *
 * <p>It only needs the official Spring Cloud Gateway extension points
 * ({@link RouteDefinitionLocator}, the route predicate factory naming convention), no Spring Boot
 * or SCG internals are touched.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass({RouteDefinitionLocator.class, DiscoveryClient.class})
@ConditionalOnProperty(prefix = PolyGatewayGatewayProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(PolyGatewayGatewayProperties.class)
public class PolyGatewayGatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DiscoveryClient.class)
    public WebEndpointRouteDefinitionLocator webEndpointRouteDefinitionLocator(DiscoveryClient discoveryClient,
                                                                               PolyGatewayGatewayProperties properties) {
        return new WebEndpointRouteDefinitionLocator(discoveryClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsumesRoutePredicateFactory consumesRoutePredicateFactory() {
        return new ConsumesRoutePredicateFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProducesRoutePredicateFactory producesRoutePredicateFactory() {
        return new ProducesRoutePredicateFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public PolyGatewayRouteRefreshListener polyGatewayRouteRefreshListener(ApplicationEventPublisher eventPublisher) {
        return new PolyGatewayRouteRefreshListener(eventPublisher);
    }
}
