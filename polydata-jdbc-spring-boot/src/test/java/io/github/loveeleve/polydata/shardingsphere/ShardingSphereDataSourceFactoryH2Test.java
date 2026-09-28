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

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static io.github.loveeleve.polydata.util.DynamicJdbcUtils.loadShardingSphereYamlResource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link YamlShardingSphereDataSourceFactory} with H2 Test : the ShardingSphere JDBC 5.5.1
 * {@link DataSource} is built directly from the datasource map and the ShardingSphere YAML
 * configuration resource, no Spring Boot starter required.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ShardingSphereDataSourceFactoryH2Test {

    private static final String DATASOURCE_NAME = "ds";

    private static final String JDBC_URL = "jdbc:h2:mem:polydata-sharding-factory;DB_CLOSE_DELAY=-1";

    @Test
    void testShardingTables() throws Exception {
        HikariDataSource hikariDataSource = new HikariDataSource();
        hikariDataSource.setJdbcUrl(JDBC_URL);
        hikariDataSource.setUsername("sa");
        hikariDataSource.setPassword("sa");

        createShardingTables(hikariDataSource);

        byte[] yamlContent = loadShardingSphereYamlResource(new DefaultResourceLoader(),
                "classpath:/META-INF/sharding-sphere/config/sharding-tables.yaml");

        DataSource shardingSphereDataSource = YamlShardingSphereDataSourceFactory.createDataSource(
                Map.of(DATASOURCE_NAME, hikariDataSource), yamlContent);

        try (Connection connection = shardingSphereDataSource.getConnection()) {
            assertNotNull(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO t_order (order_id, user_id, status) VALUES (1, 1, 'OK')");
                statement.executeUpdate("INSERT INTO t_order (order_id, user_id, status) VALUES (2, 1, 'OK')");
                try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM t_order")) {
                    assertTrue(resultSet.next());
                    assertEquals(2, resultSet.getInt(1));
                }
            }
        }

        // Verify the routing results : the odd order_id is routed to t_order_1, the even one to
        // t_order_0 (t_order_${order_id % 2})
        assertEquals(1, count(hikariDataSource, "t_order_0"));
        assertEquals(1, count(hikariDataSource, "t_order_1"));
    }

    private void createShardingTables(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
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

    private int count(DataSource dataSource, String tableName) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
