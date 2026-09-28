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
package io.github.loveeleve.polydata.support;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.Set;

import static io.github.loveeleve.polydata.support.PropertySourcesUtils.findConfiguredPropertySourceName;
import static io.github.loveeleve.polydata.support.PropertySourcesUtils.findPropertyNamesByPrefix;
import static io.github.loveeleve.polydata.support.PropertySourcesUtils.getSubProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PropertySourcesUtils} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class PropertySourcesUtilsTest {

    @Test
    void testGetSubProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("polydata.jdbc.modules.datasource.enabled", "true");
        environment.setProperty("polydata.jdbc.modules.datasource.auto-configuration.base-packages", "com.example.");
        environment.setProperty("polydata.jdbc.modules.datasource.url.default-scheme", "jdbc:mysql//");
        environment.setProperty("polydata.jdbc.other", "other");

        Map<String, Object> subProperties = getSubProperties(environment, "polydata.jdbc.modules.datasource");

        assertEquals("true", subProperties.get("enabled"));
        assertEquals("com.example.", subProperties.get("auto-configuration.base-packages"));
        assertEquals("jdbc:mysql//", subProperties.get("url.default-scheme"));
        assertEquals(3, subProperties.size());
    }

    @Test
    void testGetSubPropertiesResolvesPlaceholders() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.name", "polydata");
        environment.setProperty("polydata.jdbc.configs.primary", "${app.name}");

        Map<String, Object> subProperties = getSubProperties(environment, "polydata.jdbc.configs");

        assertEquals("polydata", subProperties.get("primary"));
    }

    @Test
    void testFindPropertyNamesByPrefix() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("polydata.jdbc.configs.primary", "{}");
        environment.setProperty("polydata.jdbc.configs.secondary", "{}");
        environment.setProperty("polydata.jdbc.enabled", "true");

        Set<String> propertyNames = findPropertyNamesByPrefix(environment, "polydata.jdbc.configs.");

        assertEquals(2, propertyNames.size());
        assertTrue(propertyNames.contains("polydata.jdbc.configs.primary"));
        assertTrue(propertyNames.contains("polydata.jdbc.configs.secondary"));
    }

    @Test
    void testFindConfiguredPropertySourceName() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("polydata.jdbc.configs.primary", "{}");

        assertEquals("mockProperties", findConfiguredPropertySourceName(environment, "polydata.jdbc.configs.primary"));
        assertNull(findConfiguredPropertySourceName(environment, "not.exists"));
    }
}
