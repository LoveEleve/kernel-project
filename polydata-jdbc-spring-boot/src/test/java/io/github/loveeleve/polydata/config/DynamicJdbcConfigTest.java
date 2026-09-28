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
package io.github.loveeleve.polydata.config;

import io.github.loveeleve.polydata.AbstractTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DynamicJdbcConfig} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class DynamicJdbcConfigTest extends AbstractTest {

    private static final String CONFIG_LOCATION = "META-INF/dynamic-jdbc/test-config.json";

    @Test
    void testParse() {
        DynamicJdbcConfig dynamicJdbcConfig = fromJsonResource(CONFIG_LOCATION, DynamicJdbcConfig.class);

        assertEquals("test", dynamicJdbcConfig.getName());
        assertTrue(dynamicJdbcConfig.isDynamic());
        assertFalse(dynamicJdbcConfig.isPrimary());

        assertTrue(dynamicJdbcConfig.hasDataSource());
        assertFalse(dynamicJdbcConfig.hasOnlySingleDataSource());
        assertFalse(dynamicJdbcConfig.hasHighAvailabilityDataSource());
        assertTrue(dynamicJdbcConfig.hasTransaction());
        assertTrue(dynamicJdbcConfig.hasShardingDataSource());
        assertTrue(dynamicJdbcConfig.hasMybatis());
        assertTrue(dynamicJdbcConfig.hasMybatisPlus());

        assertEquals(2, dynamicJdbcConfig.getDataSourceSize());
        assertEquals(0, dynamicJdbcConfig.getHighAvailabilityDataSourceZoneSize());

        assertEquals("classpath:/META-INF/sharding-sphere/config.yaml",
                dynamicJdbcConfig.getShardingSphere().getConfigResource());
        assertEquals("tx1", dynamicJdbcConfig.getTransaction().getName());
        assertEquals(3, dynamicJdbcConfig.getTransaction().getProperties().get("defaultTimeout"));
        assertEquals("io.github.loveeleve.polydata", dynamicJdbcConfig.getMybatis().getBasePackages());
        assertEquals("io.github.loveeleve.polydata.config", dynamicJdbcConfig.getMybatisPlus().getBasePackages());

        List<Map<String, String>> dataSourcePropertiesList = dynamicJdbcConfig.getDataSourcePropertiesList();
        assertEquals(2, dataSourcePropertiesList.size());
        assertEquals("ds1", dataSourcePropertiesList.get(0).get("name"));
        assertEquals("jdbc:derby:db/ds2;create=true", dataSourcePropertiesList.get(1).get("url"));

        Map<String, Map<String, String>> dataSourcePropertiesMap = dynamicJdbcConfig.getDataSourcePropertiesMap();
        assertEquals(2, dataSourcePropertiesMap.size());
        assertTrue(dataSourcePropertiesMap.containsKey("ds1"));
        assertTrue(dataSourcePropertiesMap.containsKey("ds2"));
    }

    @Test
    void testClone() {
        DynamicJdbcConfig dynamicJdbcConfig = fromJsonResource(CONFIG_LOCATION, DynamicJdbcConfig.class);

        DynamicJdbcConfig cloned = io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils
                .cloneDynamicJdbcConfig(dynamicJdbcConfig);

        assertNotNull(cloned);
        assertEquals(dynamicJdbcConfig.getName(), cloned.getName());
        assertEquals(dynamicJdbcConfig.getDataSourceSize(), cloned.getDataSourceSize());
        assertEquals(dynamicJdbcConfig.toString(), cloned.toString());
    }

    @Test
    void testHighAvailabilityDataSource() {
        DynamicJdbcConfig dynamicJdbcConfig = fromJsonResource("META-INF/sharding-sphere/sharding-tables.json",
                DynamicJdbcConfig.class);

        assertEquals(0, dynamicJdbcConfig.getDataSourceSize());
        assertEquals(2, dynamicJdbcConfig.getHighAvailabilityDataSourceZoneSize());
        assertFalse(dynamicJdbcConfig.hasDataSource());
        assertTrue(dynamicJdbcConfig.hasOnlySingleDataSource());
        assertTrue(dynamicJdbcConfig.hasHighAvailabilityDataSource());

        // The default zone is used when no ZoneContext is selected
        List<Map<String, String>> dataSourcePropertiesList = dynamicJdbcConfig.getDataSourcePropertiesList();
        assertEquals(1, dataSourcePropertiesList.size());
        assertEquals("ds", dataSourcePropertiesList.get(0).get("name"));

        Map<String, Map<String, String>> dataSourcePropertiesMap = dynamicJdbcConfig.getDataSourcePropertiesMap();
        assertEquals(1, dataSourcePropertiesMap.size());
        assertTrue(dataSourcePropertiesMap.containsKey("ds"));
    }
}
