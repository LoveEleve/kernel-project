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
package io.github.loveeleve.polyzone.spring.cloud;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;

import java.util.HashMap;
import java.util.Map;

import static io.github.loveeleve.polyzone.ZoneConstants.ZONE_PROPERTY_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link CloudServerZoneResolver}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class CloudServerZoneResolverTest {

    private final CloudServerZoneResolver resolver = new CloudServerZoneResolver();

    @Test
    void testResolveZoneFromMetadata() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(ZONE_PROPERTY_NAME, "eu-west-1");
        ServiceInstance serviceInstance = new DefaultServiceInstance("id-1", "test-service", "host", 8080, false,
                metadata);
        assertEquals("eu-west-1", resolver.resolve(serviceInstance));
    }

    @Test
    void testResolveWithoutZoneMetadata() {
        ServiceInstance serviceInstance = new DefaultServiceInstance("id-1", "test-service", "host", 8080, false,
                Map.of());
        assertNull(resolver.resolve(serviceInstance));
    }

    @Test
    void testResolveWithNullMetadata() {
        ServiceInstance serviceInstance = new ServiceInstance() {
            @Override
            public String getServiceId() {
                return "test-service";
            }

            @Override
            public String getHost() {
                return "host";
            }

            @Override
            public int getPort() {
                return 8080;
            }

            @Override
            public boolean isSecure() {
                return false;
            }

            @Override
            public java.net.URI getUri() {
                return java.net.URI.create("http://host:8080");
            }

            @Override
            public Map<String, String> getMetadata() {
                return null;
            }
        };
        assertNull(resolver.resolve(serviceInstance));
    }
}
