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

import io.github.loveeleve.polygateway.provider.PolyGatewayProviderListener;
import io.github.loveeleve.polygateway.provider.WebEndpointMappingsCollector;
import io.github.loveeleve.polygateway.provider.WebEndpointMetadataPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.event.InstancePreRegisteredEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The auto-configuration of the PolyGateway provider side: it collects the web endpoints of a
 * Spring MVC application and publishes them into the service instance metadata before the
 * registration.
 *
 * <p>It only needs the official Spring extension points (annotations, the listener registration),
 * no Spring Boot internals are touched.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass({RequestMappingHandlerMapping.class, InstancePreRegisteredEvent.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = PolyGatewayProviderProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(PolyGatewayProviderProperties.class)
public class PolyGatewayProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WebEndpointMappingsCollector webEndpointMappingsCollector() {
        return new WebEndpointMappingsCollector();
    }

    @Bean
    @ConditionalOnMissingBean
    public WebEndpointMetadataPublisher webEndpointMetadataPublisher(ApplicationContext applicationContext,
                                                                     WebEndpointMappingsCollector collector) {
        return new WebEndpointMetadataPublisher(applicationContext, collector);
    }

    @Bean
    @ConditionalOnMissingBean
    public PolyGatewayProviderListener polyGatewayProviderListener(WebEndpointMetadataPublisher publisher) {
        return new PolyGatewayProviderListener(publisher);
    }
}
