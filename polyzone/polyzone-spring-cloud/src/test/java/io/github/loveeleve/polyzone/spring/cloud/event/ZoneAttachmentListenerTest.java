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
package io.github.loveeleve.polyzone.spring.cloud.event;

import io.github.loveeleve.polyzone.ZoneAttachmentHandler;
import io.github.loveeleve.polyzone.ZoneContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.event.InstancePreRegisteredEvent;
import org.springframework.cloud.client.serviceregistry.Registration;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static io.github.loveeleve.polyzone.ZoneConstants.ZONE_PROPERTY_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for {@link ZoneAttachmentListener}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ZoneAttachmentListenerTest {

    private ZoneContext zoneContext;

    private ZoneAttachmentListener listener;

    @BeforeEach
    void setUp() {
        zoneContext = ZoneContext.get();
        zoneContext.reset();
        listener = new ZoneAttachmentListener(new ZoneAttachmentHandler(zoneContext));
    }

    @AfterEach
    void tearDown() {
        zoneContext.reset();
    }

    @Test
    void testAttachZoneOnInstancePreRegisteredEvent() {
        zoneContext.setZone("eu-west-1");
        TestRegistration registration = new TestRegistration();

        listener.onApplicationEvent(new InstancePreRegisteredEvent(this, registration));

        assertEquals("eu-west-1", registration.getMetadata().get(ZONE_PROPERTY_NAME));
    }

    @Test
    void testNothingAttachedWhenZoneIsBlank() {
        zoneContext.setZone(" ");
        TestRegistration registration = new TestRegistration();

        listener.onApplicationEvent(new InstancePreRegisteredEvent(this, registration));

        assertFalse(registration.getMetadata().containsKey(ZONE_PROPERTY_NAME));
    }

    static class TestRegistration implements Registration {

        private final Map<String, String> metadata = new HashMap<>();

        @Override
        public String getServiceId() {
            return "test-service";
        }

        @Override
        public String getHost() {
            return "localhost";
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
        public URI getUri() {
            return URI.create("http://localhost:8080");
        }

        @Override
        public Map<String, String> getMetadata() {
            return metadata;
        }
    }
}
