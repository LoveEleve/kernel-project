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

import static io.github.loveeleve.polyzone.spring.aws.Ec2AvailabilityZoneEndpointZoneLocator.AVAILABILITY_ZONE_ENDPOINT_URI_PROPERTY_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Ec2AvailabilityZoneEndpointZoneLocator}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class Ec2AvailabilityZoneEndpointZoneLocatorTest {

    @Test
    void testLocateFromMetadataEndpoint() throws IOException {
        String expectedZone = "us-east-1a";
        HttpServer server = startServer(expectedZone);
        try {
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty(AVAILABILITY_ZONE_ENDPOINT_URI_PROPERTY_NAME, url(server));

            Ec2AvailabilityZoneEndpointZoneLocator locator = new Ec2AvailabilityZoneEndpointZoneLocator();
            locator.setEnvironment(environment);

            assertTrue(locator.supports(environment));
            assertEquals(expectedZone, locator.locate(environment));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testLocateWithUnreachableEndpoint() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(AVAILABILITY_ZONE_ENDPOINT_URI_PROPERTY_NAME, "http://localhost:1/zone");

        Ec2AvailabilityZoneEndpointZoneLocator locator = new Ec2AvailabilityZoneEndpointZoneLocator();
        locator.setEnvironment(environment);

        assertNull(locator.locate(environment));
    }

    @Test
    void testDefaultOrder() {
        assertEquals(15, new Ec2AvailabilityZoneEndpointZoneLocator().getOrder());
    }

    private static HttpServer startServer(String content) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/zone", exchange -> {
            byte[] response = content.getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort() + "/zone";
    }
}
