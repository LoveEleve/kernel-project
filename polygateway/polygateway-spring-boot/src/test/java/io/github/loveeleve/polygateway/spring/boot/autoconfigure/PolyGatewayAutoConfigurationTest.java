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
import io.github.loveeleve.polygateway.provider.PolyGatewayProviderListener;
import io.github.loveeleve.polygateway.provider.WebEndpointMappingsCollector;
import io.github.loveeleve.polygateway.provider.WebEndpointMetadataPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The auto-configuration conditions of PolyGateway.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class PolyGatewayAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PolyGatewayProviderAutoConfiguration.class,
                    PolyGatewayGatewayAutoConfiguration.class));

    @Test
    void testDisabledByDefault() {
        contextRunner
                .withPropertyValues("polygateway.provider.enabled=false", "polygateway.gateway.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(WebEndpointMappingsCollector.class);
                    assertThat(context).doesNotHaveBean(WebEndpointMetadataPublisher.class);
                    assertThat(context).doesNotHaveBean(PolyGatewayProviderListener.class);
                    assertThat(context).doesNotHaveBean(WebEndpointRouteDefinitionLocator.class);
                    assertThat(context).doesNotHaveBean(ConsumesRoutePredicateFactory.class);
                    assertThat(context).doesNotHaveBean(ProducesRoutePredicateFactory.class);
                    assertThat(context).doesNotHaveBean(PolyGatewayRouteRefreshListener.class);
                });
    }

    @Test
    void testGatewayAutoConfiguration() {
        contextRunner
                .withBean(DiscoveryClient.class, () -> mock(DiscoveryClient.class))
                .withPropertyValues(
                        "polygateway.provider.enabled=false",
                        "polygateway.gateway.enabled=true",
                        "polygateway.gateway.excludes[0].patterns=/internal/**",
                        "polygateway.gateway.excludes[0].methods=POST")
                .run(context -> {
                    assertThat(context).hasSingleBean(WebEndpointRouteDefinitionLocator.class);
                    assertThat(context).hasSingleBean(ConsumesRoutePredicateFactory.class);
                    assertThat(context).hasSingleBean(ProducesRoutePredicateFactory.class);
                    assertThat(context).hasSingleBean(PolyGatewayRouteRefreshListener.class);
                    assertThat(context).doesNotHaveBean(WebEndpointMetadataPublisher.class);

                    PolyGatewayGatewayProperties properties = context.getBean(PolyGatewayGatewayProperties.class);
                    assertThat(properties.getExcludes()).hasSize(1);
                    assertThat(properties.getExcludes().get(0).getPatterns()).containsExactly("/internal/**");
                    assertThat(properties.getExcludes().get(0).getMethods()).containsExactly("POST");
                });
    }

    @Test
    void testGatewayLocatorNeedsDiscoveryClient() {
        contextRunner
                .withPropertyValues(
                        "polygateway.provider.enabled=false",
                        "polygateway.gateway.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(WebEndpointRouteDefinitionLocator.class);
                    assertThat(context).hasSingleBean(ConsumesRoutePredicateFactory.class);
                    assertThat(context).hasSingleBean(PolyGatewayRouteRefreshListener.class);
                });
    }
}
