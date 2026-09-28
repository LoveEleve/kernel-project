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
package io.github.loveeleve.polydata.shardingsphere.context;

import com.zaxxer.hikari.HikariDataSource;
import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the physical DataSource cleanup of
 * {@link ShardingSphereConfigurationConfigBeanDefinitionRegistrar} when the ShardingSphere
 * {@link javax.sql.DataSource} can't be created (e.g. an invalid YAML configuration).
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ShardingSphereConfigurationConfigBeanDefinitionRegistrarTest {

    @AfterEach
    void tearDown() {
        TrackingDataSource.closed = false;
    }

    @Test
    void testPhysicalDataSourcesAreClosedWhenShardingSphereDataSourceCreationFails() {
        DynamicJdbcConfig dynamicJdbcConfig = new DynamicJdbcConfig();
        dynamicJdbcConfig.setName("test-cleanup");
        dynamicJdbcConfig.setDataSource(List.of(Map.of(
                "name", "ds",
                "url", "jdbc:h2:mem:polydata-cleanup;DB_CLOSE_DELAY=-1",
                "type", TrackingDataSource.class.getName())));

        DynamicJdbcConfig.ShardingSphere shardingSphere = new DynamicJdbcConfig.ShardingSphere();
        shardingSphere.setConfigResource("classpath:/META-INF/sharding-sphere/config/invalid.yaml");
        dynamicJdbcConfig.setShardingSphere(shardingSphere);

        ShardingSphereConfigurationConfigBeanDefinitionRegistrar registrar =
                new ShardingSphereConfigurationConfigBeanDefinitionRegistrar();
        registrar.setResourceLoader(new DefaultResourceLoader());
        registrar.setBeanClassLoader(getClass().getClassLoader());
        registrar.setEnvironment(new StandardEnvironment());

        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

        assertThrows(IllegalStateException.class, () -> registrar.register(dynamicJdbcConfig,
                "polydata.jdbc.configs.testCleanup", registry));

        assertTrue(TrackingDataSource.closed,
                "The physical DataSource must be closed when the ShardingSphere DataSource can't be created");
    }

    /**
     * The Hikari {@link javax.sql.DataSource} tracking the {@code close()} invocation.
     */
    public static class TrackingDataSource extends HikariDataSource {

        static volatile boolean closed = false;

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }
}
