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
package io.github.loveeleve.polydata.util;

import io.github.loveeleve.polydata.AbstractTest;
import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.cloneDynamicJdbcConfig;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.generateDynamicDataSourceDynamicJdbcChildContextId;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.generateDynamicDataSourceDynamicJdbcConfigName;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.generateDynamicJdbcChildContextId;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.generateDynamicJdbcConfigBeanName;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.generateSynthesizedPropertySourceName;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getDataSourceName;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getDataSourcePassword;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getDataSourceType;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getDataSourceUrl;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getDataSourceUrlEntry;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getDataSourceUserName;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getDriverClassName;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getDynamicJdbcConfigs;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getModule;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.isDynamicJdbcConfig;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.parseDynamicJdbcConfig;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.resolveDynamicJdbcConfigName;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getConfigPropertyName;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DynamicJdbcConfigUtils} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class DynamicJdbcConfigUtilsTest extends AbstractTest {

    @Test
    void testGetDynamicJdbcConfigs() {
        MockEnvironment environment = new MockEnvironment();
        Map<String, DynamicJdbcConfig> dynamicJdbcConfigs = getDynamicJdbcConfigs(environment);

        assertEquals(emptyMap(), dynamicJdbcConfigs);

        environment.setProperty(getConfigPropertyName("main"), getContent("META-INF/dynamic-jdbc/sharding-tables-config.json"));
        dynamicJdbcConfigs = getDynamicJdbcConfigs(environment);

        assertEquals(1, dynamicJdbcConfigs.size());
        DynamicJdbcConfig dynamicJdbcConfig = dynamicJdbcConfigs.get("polydata.jdbc.configs.main");
        assertNotNull(dynamicJdbcConfig);
        assertEquals("sharding-tables-config", dynamicJdbcConfig.getName());
    }

    @Test
    void testGetDynamicJdbcConfig() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(getConfigPropertyName("main"), "classpath:/META-INF/dynamic-jdbc/sharding-tables-config.json");
        DynamicJdbcConfig dynamicJdbcConfig = DynamicJdbcConfigUtils.getDynamicJdbcConfig(environment, "polydata.jdbc.configs.main");
        assertEquals("sharding-tables-config", dynamicJdbcConfig.getName());
        assertTrue(dynamicJdbcConfig.hasDataSource());
    }

    @Test
    void testCloneDynamicJdbcConfig() {
        DynamicJdbcConfig dynamicJdbcConfig = getDynamicJdbcConfig();
        DynamicJdbcConfig clonedDynamicJdbcConfig = cloneDynamicJdbcConfig(dynamicJdbcConfig);
        assertEquals(dynamicJdbcConfig, clonedDynamicJdbcConfig);
        assertEquals(dynamicJdbcConfig.toString(), clonedDynamicJdbcConfig.toString());
    }

    @Test
    void testParseDynamicJdbcConfig() {
        assertThrows(IllegalArgumentException.class, () -> parseDynamicJdbcConfig("test", ""));
    }

    @Test
    void testIsDynamicJdbcConfig() {
        DynamicJdbcConfig config = getDynamicJdbcConfig();
        assertFalse(isDynamicJdbcConfig(config));

        config.setName("Dynamic#A");
        assertTrue(isDynamicJdbcConfig(config));
    }

    @Test
    void testGenerateDynamicDataSourceDynamicJdbcConfigName() {
        DynamicJdbcConfig config = getDynamicJdbcConfig();
        assertEquals("Dynamic#sharding-tables-config#datasource", generateDynamicDataSourceDynamicJdbcConfigName(config));
    }

    @Test
    void testGenerateDynamicDataSourceDynamicJdbcChildContextId() {
        DynamicJdbcConfig config = getDynamicJdbcConfig();
        String id = generateDynamicDataSourceDynamicJdbcChildContextId(config);
        assertEquals("Dynamic#DynamicJdbcChildContext[sharding-tables-config]#datasource", id);
    }

    @Test
    void testGenerateSynthesizedPropertySourceName() {
        String name = generateSynthesizedPropertySourceName("test");
        assertEquals("SynthesizedPropertySource[test]", name);
    }

    @Test
    void testGetModule() {
        assertEquals("transaction", getModule(new DynamicJdbcConfig.Transaction()));
        assertEquals("sharding-sphere", getModule(new DynamicJdbcConfig.ShardingSphere()));
        assertEquals("mybatis", getModule(new DynamicJdbcConfig.Mybatis()));
        assertEquals("mybatis-plus", getModule(new DynamicJdbcConfig.MybatisPlus()));
    }

    @Test
    void testGetModuleWithIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> getModule(DynamicJdbcConfig.Config.class));
    }

    @Test
    void testGenerateDynamicJdbcConfigBeanName() {
        String beanName = generateDynamicJdbcConfigBeanName(getDynamicJdbcConfig(), "polydata.jdbc.configs.test");
        assertEquals("DynamicJdbcConfigBean[test].sharding-tables-config", beanName);
    }

    @Test
    void testGenerateDynamicJdbcChildContextId() {
        DynamicJdbcConfig dynamicJdbcConfig = getDynamicJdbcConfig();
        String contextId = generateDynamicJdbcChildContextId(dynamicJdbcConfig);
        assertEquals("DynamicJdbcChildContext[sharding-tables-config]", contextId);
    }

    @Test
    void testResolveDynamicJdbcConfigName() {
        String name = resolveDynamicJdbcConfigName("DynamicJdbcChildContext[test]");
        assertEquals("test", name);
    }

    @Test
    void testGetDataSourceProperties() {
        DynamicJdbcConfig dynamicJdbcConfig = getDynamicJdbcConfig();
        Map<String, String> dataSource = dynamicJdbcConfig.getDataSourcePropertiesList().get(0);
        assertEquals("demo_ds", getDataSourceName(dataSource));
        assertEquals("com.zaxxer.hikari.HikariDataSource", getDataSourceType(dataSource));
        assertEquals("com.mysql.jdbc.Driver", getDriverClassName(dataSource));
        assertEquals("jdbc:mysql://127.0.0.1:3306/demo_ds?serverTimezone=UTC&useSSL=false&useUnicode=true&characterEncoding=UTF-8",
                getDataSourceUrl(dataSource));
        Map.Entry<String, String> entry = getDataSourceUrlEntry(dataSource);
        assertEquals("url", entry.getKey());
        assertEquals("jdbc:mysql://127.0.0.1:3306/demo_ds?serverTimezone=UTC&useSSL=false&useUnicode=true&characterEncoding=UTF-8", entry.getValue());
        assertEquals("root", getDataSourceUserName(dataSource));
        assertEquals("123456", getDataSourcePassword(dataSource));
    }

    private DynamicJdbcConfig getDynamicJdbcConfig() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(getConfigPropertyName("main"), getContent("META-INF/dynamic-jdbc/sharding-tables-config.json"));
        Map<String, DynamicJdbcConfig> dynamicJdbcConfigs = getDynamicJdbcConfigs(environment);
        assertEquals(1, dynamicJdbcConfigs.size());
        return dynamicJdbcConfigs.get("polydata.jdbc.configs.main");
    }

}
