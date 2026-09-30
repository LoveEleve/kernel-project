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
import io.github.loveeleve.polygateway.web.WebEndpointMappingCodec;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebEndpointMetadataPublisher} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class WebEndpointMetadataPublisherTest {

    @Test
    void testPublish() {
        GenericApplicationContext context = new GenericApplicationContext();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(WebEndpointMetadataPublisher.CONTEXT_PATH_PROPERTY_NAME, "/orders");
        context.setEnvironment(environment);
        context.refresh();

        DefaultServiceInstance serviceInstance = new DefaultServiceInstance("order-1", "order-service",
                "127.0.0.1", 8080, false);
        try {
            new WebEndpointMetadataPublisher(context).publish(serviceInstance);

            String endpoints = serviceInstance.getMetadata().get(PolyGatewayConstants.WEB_ENDPOINTS_METADATA_NAME);
            assertTrue(WebEndpointMappingCodec.decode(endpoints).isEmpty());
            assertEquals("/orders", serviceInstance.getMetadata().get(PolyGatewayConstants.CONTEXT_PATH_METADATA_NAME));
        } finally {
            context.close();
        }
    }

    @Test
    void testContextPathIsOptional() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(new MockEnvironment());
        context.refresh();

        DefaultServiceInstance serviceInstance = new DefaultServiceInstance("order-1", "order-service",
                "127.0.0.1", 8080, false);
        try {
            new WebEndpointMetadataPublisher(context).publish(serviceInstance);

            assertTrue(serviceInstance.getMetadata().containsKey(PolyGatewayConstants.WEB_ENDPOINTS_METADATA_NAME));
            assertEquals(null, serviceInstance.getMetadata().get(PolyGatewayConstants.CONTEXT_PATH_METADATA_NAME));
        } finally {
            context.close();
        }
    }
}
