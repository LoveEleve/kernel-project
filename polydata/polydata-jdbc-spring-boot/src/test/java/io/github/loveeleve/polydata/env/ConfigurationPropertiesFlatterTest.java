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
package io.github.loveeleve.polydata.env;

import io.github.loveeleve.polydata.AbstractTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link ConfigurationPropertiesFlatter} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringJUnitConfig(classes = {ConfigurationPropertiesFlatter.class, ConfigurationPropertiesFlatterTest.class})
class ConfigurationPropertiesFlatterTest extends AbstractTest {

    @Autowired
    private ConfigurationPropertiesFlatter resolver;

    @Test
    void testResolve() {
        String jsonContent = getContent("META-INF/dynamic-jdbc/test-config.json");
        Map<String, Object> nestedProperties = fromJson(jsonContent, Map.class);
        Map<String, String> flattenProperties = resolver.flat(nestedProperties);

        assertEquals("test", flattenProperties.get("name"));
        assertEquals("ds1", flattenProperties.get("datasource[0].name"));
        assertEquals("ds2", flattenProperties.get("datasource[1].name"));
        assertEquals("com.zaxxer.hikari.HikariDataSource", flattenProperties.get("datasource[0].type"));
        assertEquals("tx1", flattenProperties.get("transaction.name"));
        assertEquals("3", flattenProperties.get("transaction.properties.defaultTimeout"));
        assertEquals("true", flattenProperties.get("transaction.properties.rollbackOnCommitFailure"));
    }

    @Test
    void testFlatWithNullElement() {
        Map<String, Object> nestedProperties = new LinkedHashMap<>();
        nestedProperties.put("values", Arrays.asList("a", null, "b"));

        Map<String, String> flattenProperties = resolver.flat(nestedProperties);

        assertEquals("a", flattenProperties.get("values[0]"));
        assertEquals("b", flattenProperties.get("values[2]"));
        assertFalse(flattenProperties.containsKey("values[1]"));
    }
}
