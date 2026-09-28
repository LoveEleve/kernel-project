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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed {@link DynamicDataSource} Test : multiple DynamicJdbcConfigs with isolated child contexts
 * and promoted parent beans
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringBootTest(classes = DynamicDataSourceMultipleContextH2Test.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "classpath:/META-INF/dynamic-jdbc/h2-multiple-configs.properties")
class DynamicDataSourceMultipleContextH2Test {

    @Autowired
    private Map<String, DynamicJdbcConfig> dynamicJdbcConfigs;

    @Autowired
    private Map<String, DataSource> dataSourceMap;

    @Test
    void test() throws Exception {
        // Both DynamicJdbcConfigs are promoted from their isolated child contexts
        assertEquals(2, dynamicJdbcConfigs.size());
        assertEquals(2, dataSourceMap.size());

        Set<String> jdbcURLs = new HashSet<>(dataSourceMap.size());

        for (Map.Entry<String, DataSource> entry : dataSourceMap.entrySet()) {
            DataSource dataSource = entry.getValue();
            assertTrue(dataSource instanceof DynamicDataSource, entry.getKey());
            assertTrue(entry.getKey().endsWith("$DynamicJdbcDynamicDataSource"), entry.getKey());
            try (Connection connection = dataSource.getConnection()) {
                jdbcURLs.add(connection.getMetaData().getURL());
            }
        }

        assertTrue(jdbcURLs.contains("jdbc:h2:mem:polydata-one"), jdbcURLs.toString());
        assertTrue(jdbcURLs.contains("jdbc:h2:mem:polydata-two"), jdbcURLs.toString());
    }
}
