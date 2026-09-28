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
package io.github.loveeleve.polydata.datasource;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polyconfig.spring.event.ConfigPropertiesChangedEvent;
import io.github.loveeleve.polyconfig.spring.event.ConfigPropertyChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed {@link DynamicDataSource} Test : single DynamicJdbcConfig and configuration hot swap
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringBootTest(classes = DynamicDataSourceH2Test.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "classpath:/META-INF/dynamic-jdbc/h2-single-config.properties")
class DynamicDataSourceH2Test {

    private static final String CONFIG_PROPERTY_NAME = "polydata.jdbc.configs.primary";

    @Autowired
    private Map<String, DynamicJdbcConfig> dynamicJdbcConfigs;

    @Autowired
    private Map<String, DataSource> dataSourceMap;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private ConfigurableEnvironment environment;

    @Test
    void test() throws Exception {
        assertEquals(1, dynamicJdbcConfigs.size());

        DynamicJdbcConfig dynamicJdbcConfig = dynamicJdbcConfigs.get("DynamicJdbcConfigBean[primary].h2-primary");
        assertNotNull(dynamicJdbcConfig);
        assertEquals("h2-primary", dynamicJdbcConfig.getName());
        // The 'datasource' configuration is moved into the DynamicDataSource clone on purpose,
        // the promoted DynamicJdbcConfig no longer carries the datasource module
        assertFalse(dynamicJdbcConfig.hasDataSource());

        DataSource dataSource = dataSourceMap.get("DynamicJdbcDynamicDataSource");
        assertNotNull(dataSource);
        assertTrue(dataSource instanceof DynamicDataSource);

        assertEquals("jdbc:h2:mem:polydata-primary", getJdbcURL(dataSource));

        // The configuration change triggers the DataSource hot swap
        String newConfigValue = "{\"name\":\"h2-primary\",\"datasource\":[{\"name\":\"ds\","
                + "\"url\":\"jdbc:h2:mem:polydata-primary-v2;DB_CLOSE_DELAY=-1\"}]}";
        environment.getPropertySources().addFirst(
                new MapPropertySource("h2-config-change", singletonMap(CONFIG_PROPERTY_NAME, newConfigValue)));
        context.publishEvent(new ConfigPropertiesChangedEvent(context, "test",
                List.of(new ConfigPropertyChangedEvent(CONFIG_PROPERTY_NAME, null, newConfigValue))));

        assertEquals("jdbc:h2:mem:polydata-primary-v2", getJdbcURL(dataSource));
    }

    private String getJdbcURL(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }
}
