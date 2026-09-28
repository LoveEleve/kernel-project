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
package io.github.loveeleve.polydata.shardingsphere;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import org.apache.shardingsphere.driver.jdbc.core.datasource.ShardingSphereDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed ShardingSphere integration Test : the ShardingSphere {@link DataSource} is created and
 * registered in the child context by
 * {@link io.github.loveeleve.polydata.shardingsphere.context.ShardingSphereConfigurationConfigBeanDefinitionRegistrar},
 * and the promoted {@link DataSource} is the delegate of the {@code DynamicDataSource}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringBootTest(classes = ShardingSphereIntegrationH2Test.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "classpath:/META-INF/dynamic-jdbc/h2-sharding-config.properties")
@ContextConfiguration(initializers = ShardingSphereIntegrationH2Test.ShardingTablesInitializer.class)
class ShardingSphereIntegrationH2Test {

    private static final String JDBC_URL = "jdbc:h2:mem:polydata-sharding;DB_CLOSE_DELAY=-1";

    /**
     * The ShardingSphere DataSource loads the actual table metadata when it is created, so the
     * actual sharding tables must exist before the DynamicDataSource is initialized (which happens
     * on the {@code ApplicationPreparedEvent}, after the initializers have been applied).
     */
    static class ShardingTablesInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            try {
                createShardingTables();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Autowired
    private Map<String, DynamicJdbcConfig> dynamicJdbcConfigs;

    @Autowired
    private Map<String, DataSource> dataSourceMap;

    @Test
    void test() throws Exception {
        assertEquals(1, dynamicJdbcConfigs.size());
        assertEquals(1, dataSourceMap.size());

        DataSource dataSource = dataSourceMap.get("DynamicJdbcDynamicDataSource");
        assertNotNull(dataSource);
        assertEquals(ShardingSphereDataSource.class, dataSource.unwrap(DataSource.class).getClass());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO t_order (order_id, user_id, status) VALUES (1, 1, 'OK')");
            statement.executeUpdate("INSERT INTO t_order (order_id, user_id, status) VALUES (2, 1, 'OK')");
            try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM t_order")) {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt(1));
            }
        }
    }

    private static void createShardingTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "sa");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE t_order_0 (order_id BIGINT PRIMARY KEY, user_id INT, status VARCHAR(50))");
            statement.executeUpdate("CREATE TABLE t_order_1 (order_id BIGINT PRIMARY KEY, user_id INT, status VARCHAR(50))");
            statement.executeUpdate("CREATE TABLE t_order_item_0 (order_item_id BIGINT PRIMARY KEY, order_id BIGINT, user_id INT, status VARCHAR(50))");
            statement.executeUpdate("CREATE TABLE t_order_item_1 (order_item_id BIGINT PRIMARY KEY, order_id BIGINT, user_id INT, status VARCHAR(50))");
            statement.executeUpdate("CREATE TABLE t_account_0 (account_id BIGINT PRIMARY KEY, user_id INT, status VARCHAR(50))");
            statement.executeUpdate("CREATE TABLE t_account_1 (account_id BIGINT PRIMARY KEY, user_id INT, status VARCHAR(50))");
            statement.executeUpdate("CREATE TABLE t_address (address_id BIGINT PRIMARY KEY, address_name VARCHAR(50))");
        }
    }
}
