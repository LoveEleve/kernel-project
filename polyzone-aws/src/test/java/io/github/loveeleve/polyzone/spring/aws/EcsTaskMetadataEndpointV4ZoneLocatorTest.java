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
package io.github.loveeleve.polyzone.spring.aws;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import static io.github.loveeleve.polyzone.spring.aws.EcsTaskMetadataEndpointV4ZoneLocator.METADATA_URI_V4_ENV_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EcsTaskMetadataEndpointV4ZoneLocator}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class EcsTaskMetadataEndpointV4ZoneLocatorTest {

    @Test
    void testLocateFromTaskMetadataEndpoint() throws IOException {
        String expectedZone = "ap-southeast-1b";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/task", exchange -> {
            byte[] response = ("{\"AvailabilityZone\":\"" + expectedZone + "\"}").getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        try {
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty(METADATA_URI_V4_ENV_NAME, "http://localhost:" + server.getAddress().getPort());

            EcsTaskMetadataEndpointV4ZoneLocator locator = new EcsTaskMetadataEndpointV4ZoneLocator();
            locator.setEnvironment(environment);

            assertTrue(locator.supports(environment));
            assertEquals(expectedZone, locator.locate(environment));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testNotSupportedWithoutEnvironmentVariable() {
        MockEnvironment environment = new MockEnvironment();
        EcsTaskMetadataEndpointV4ZoneLocator locator = new EcsTaskMetadataEndpointV4ZoneLocator();
        assertFalse(locator.supports(environment));
        assertNull(locator.locate(environment));
    }

    @Test
    void testLocateWithUnreachableEndpoint() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(METADATA_URI_V4_ENV_NAME, "http://localhost:1");

        EcsTaskMetadataEndpointV4ZoneLocator locator = new EcsTaskMetadataEndpointV4ZoneLocator();
        locator.setEnvironment(environment);

        assertNull(locator.locate(environment));
    }

    @Test
    void testDefaultOrder() {
        assertEquals(10, new EcsTaskMetadataEndpointV4ZoneLocator().getOrder());
    }
}
