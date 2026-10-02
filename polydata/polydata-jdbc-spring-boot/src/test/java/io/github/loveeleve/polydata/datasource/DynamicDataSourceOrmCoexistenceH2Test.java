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
import io.github.loveeleve.polydata.datasource.orm.mybatis.mapper.UserMapper;
import io.github.loveeleve.polydata.datasource.orm.mybatisplus.entity.Order;
import io.github.loveeleve.polydata.datasource.orm.mybatisplus.mapper.OrderMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * H2-backed ORM coexistence Test : MyBatis and MyBatis-Plus run in two isolated child contexts of
 * one JVM and both mappers work against their own datasource.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringBootTest(classes = DynamicDataSourceOrmCoexistenceH2Test.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "classpath:/META-INF/dynamic-jdbc/h2-orm-coexist.properties")
@ContextConfiguration(initializers = DynamicDataSourceOrmCoexistenceH2Test.TablesInitializer.class)
class DynamicDataSourceOrmCoexistenceH2Test {

    private static final String MYBATIS_JDBC_URL = "jdbc:h2:mem:polydata-orm-mybatis;DB_CLOSE_DELAY=-1";

    private static final String MYBATIS_PLUS_JDBC_URL = "jdbc:h2:mem:polydata-orm-mybatisplus;DB_CLOSE_DELAY=-1";

    /**
     * The physical tables must exist before the child contexts create their DataSources.
     */
    static class TablesInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            try {
                execute(MYBATIS_JDBC_URL, "CREATE TABLE IF NOT EXISTS t_user (id BIGINT PRIMARY KEY, name VARCHAR(64))");
                execute(MYBATIS_PLUS_JDBC_URL, "CREATE TABLE IF NOT EXISTS t_order (id BIGINT PRIMARY KEY, status VARCHAR(32))");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private void execute(String jdbcURL, String sql) throws Exception {
            try (Connection connection = DriverManager.getConnection(jdbcURL, "sa", "sa");
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }
    }

    @Autowired
    private Map<String, DynamicJdbcConfig> dynamicJdbcConfigs;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void test() {
        // Two isolated child contexts, one per ORM framework
        assertEquals(2, dynamicJdbcConfigs.size());

        // The MyBatis Mapper works against its own datasource
        userMapper.insert(1L, "alice");
        assertEquals("alice", userMapper.findName(1L));

        // The MyBatis-Plus Mapper works against its own datasource
        Order order = new Order();
        order.setId(100L);
        order.setStatus("CREATED");
        orderMapper.insert(order);
        Order loaded = orderMapper.selectById(100L);
        assertNotNull(loaded);
        assertEquals("CREATED", loaded.getStatus());

        // The two ORM stacks coexist : two SqlSessionFactory beans promoted from the child contexts
        assertEquals(2, context.getBeansOfType(SqlSessionFactory.class).size());
    }
}
