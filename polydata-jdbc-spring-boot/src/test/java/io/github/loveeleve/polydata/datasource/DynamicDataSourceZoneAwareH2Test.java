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

import io.github.loveeleve.polyzone.ZoneContext;
import io.github.loveeleve.polyzone.spring.event.ZoneContextChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.beans.PropertyChangeEvent;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * H2-backed zone-aware High Availability {@link DynamicDataSource} Test : the DataSource is rebuilt
 * on the {@link ZoneContextChangedEvent}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringBootTest(classes = DynamicDataSourceZoneAwareH2Test.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "classpath:/META-INF/dynamic-jdbc/h2-zone-config.properties")
class DynamicDataSourceZoneAwareH2Test {

    @Autowired
    private Map<String, DataSource> dataSourceMap;

    @Autowired
    private ConfigurableApplicationContext context;

    @AfterEach
    void resetZoneContext() {
        ZoneContext.get().reset();
    }

    @Test
    void testZoneChangeRebuildsDataSource() throws Exception {
        DataSource dataSource = dataSourceMap.get("DynamicJdbcDynamicDataSource");
        assertNotNull(dataSource);

        // The default zone is used without any zone change
        assertEquals("jdbc:h2:mem:polydata-zone-default", getJdbcURL(dataSource));

        ZoneContext zoneContext = ZoneContext.get();
        zoneContext.setZone("test-zone");

        context.publishEvent(new ZoneContextChangedEvent(context, zoneContext,
                List.of(new PropertyChangeEvent(zoneContext, "zone", DEFAULT_ZONE, "test-zone"))));

        assertEquals("jdbc:h2:mem:polydata-zone-test", getJdbcURL(dataSource));
    }

    private String getJdbcURL(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }
}
