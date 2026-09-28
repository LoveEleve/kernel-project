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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.loveeleve.polyzone.spring.aws.EcsContainerMetadataFileZoneLocator.METADATA_FILE_ENV_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EcsContainerMetadataFileZoneLocator}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class EcsContainerMetadataFileZoneLocatorTest {

    @Test
    void testLocateFromMetadataFile(@TempDir Path tempDir) throws IOException {
        Path metadataFile = tempDir.resolve("ecs-container-metadata.json");
        Files.writeString(metadataFile, "{\"AvailabilityZone\":\"eu-west-1a\",\"DockerId\":\"test\"}");

        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(METADATA_FILE_ENV_NAME, metadataFile.toString());

        EcsContainerMetadataFileZoneLocator locator = new EcsContainerMetadataFileZoneLocator();

        assertTrue(locator.supports(environment));
        assertEquals("eu-west-1a", locator.locate(environment));
    }

    @Test
    void testNotSupportedWithoutEnvironmentVariable() {
        MockEnvironment environment = new MockEnvironment();
        EcsContainerMetadataFileZoneLocator locator = new EcsContainerMetadataFileZoneLocator();
        assertFalse(locator.supports(environment));
        assertNull(locator.locate(environment));
    }

    @Test
    void testLocateWithMissingFile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(METADATA_FILE_ENV_NAME, "/not/exist/ecs-container-metadata.json");

        EcsContainerMetadataFileZoneLocator locator = new EcsContainerMetadataFileZoneLocator();

        assertTrue(locator.supports(environment));
        assertNull(locator.locate(environment));
    }

    @Test
    void testLocateWithInvalidJson(@TempDir Path tempDir) throws IOException {
        Path metadataFile = tempDir.resolve("invalid.json");
        Files.writeString(metadataFile, "not-a-json");

        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(METADATA_FILE_ENV_NAME, metadataFile.toString());

        EcsContainerMetadataFileZoneLocator locator = new EcsContainerMetadataFileZoneLocator();

        assertNull(locator.locate(environment));
    }

    @Test
    void testDefaultOrder() {
        assertEquals(5, new EcsContainerMetadataFileZoneLocator().getOrder());
    }
}
