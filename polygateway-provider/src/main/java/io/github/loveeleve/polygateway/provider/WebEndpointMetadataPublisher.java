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

import io.github.loveeleve.polygateway.PolyGatewayConstants;
import io.github.loveeleve.polygateway.web.WebEndpointMapping;
import io.github.loveeleve.polygateway.web.WebEndpointMappingCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Publishes the web endpoints and the context path of the application into the metadata of the
 * service instance, so that the gateway can discover them.
 *
 * <p>The metadata is read by the gateway through the discovery client, therefore it must be
 * published before the service instance is registered - see
 * {@link PolyGatewayProviderListener}, which triggers the publication on the
 * {@code InstancePreRegisteredEvent}.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   WebEndpointMetadataPublisher publisher = new WebEndpointMetadataPublisher(applicationContext);
 *   publisher.publish(serviceInstance);
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class WebEndpointMetadataPublisher {

    private static final Logger logger = LoggerFactory.getLogger(WebEndpointMetadataPublisher.class);

    /**
     * The property name of the servlet context path of the application.
     */
    public static final String CONTEXT_PATH_PROPERTY_NAME = "server.servlet.context-path";

    private final ApplicationContext context;

    private final WebEndpointMappingsCollector collector;

    public WebEndpointMetadataPublisher(ApplicationContext context) {
        this(context, new WebEndpointMappingsCollector());
    }

    public WebEndpointMetadataPublisher(ApplicationContext context, WebEndpointMappingsCollector collector) {
        this.context = context;
        this.collector = collector;
    }

    /**
     * Collects the web endpoints of the application and publishes them (plus the context path)
     * into the metadata of the given service instance.
     *
     * @param serviceInstance the service instance about to be registered
     */
    public void publish(ServiceInstance serviceInstance) {
        List<WebEndpointMapping> mappings = collector.collect(context);
        String endpoints = WebEndpointMappingCodec.encode(mappings);
        Map<String, String> metadata = serviceInstance.getMetadata();
        putMetadata(metadata, PolyGatewayConstants.WEB_ENDPOINTS_METADATA_NAME, endpoints);

        String contextPath = context.getEnvironment().getProperty(CONTEXT_PATH_PROPERTY_NAME);
        if (StringUtils.hasText(contextPath)) {
            putMetadata(metadata, PolyGatewayConstants.CONTEXT_PATH_METADATA_NAME, contextPath);
        }
        logger.info("Published {} web endpoint(s) into the metadata of the service instance [{}]",
                mappings.size(), serviceInstance.getServiceId());
    }

    private void putMetadata(Map<String, String> metadata, String name, String value) {
        try {
            metadata.put(name, value);
        } catch (UnsupportedOperationException e) {
            logger.warn("The metadata of the service instance [{}] is immutable, the [{}] is not published",
                    context.getId(), name);
        }
    }
}
