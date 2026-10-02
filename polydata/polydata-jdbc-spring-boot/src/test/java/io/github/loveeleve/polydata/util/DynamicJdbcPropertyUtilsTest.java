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

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.github.loveeleve.polydata.AbstractTest.ofSet;
import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.AUTO_CONFIGURATION_BASE_PACKAGES_PROPERTY_NAME;
import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.DATASOURCE_MODULE;
import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.HIGH_AVAILABILITY_DATASOURCE_MODULE;
import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.MYBATIS_MODULE;
import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.MYBATIS_PLUS_MODULE;
import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.SHARDING_SPHERE_MODULE;
import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.TRANSACTION_MODULE;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.appendPropertyValue;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getAllModulesAutoConfigurationBasePackages;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getAllModulesPropertyValues;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getClusterDataSourcePassword;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getClusterDataSourceUserName;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getDataSourceDefaultPassword;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getDataSourceDefaultUserName;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getDataSourceUrlDefaultQueryParams;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getModuleAutoConfigurationBannedModules;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getModuleAutoConfigurationBasePackages;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getModuleExclusionAutoConfigurationClassNames;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getModulePropertyNameAliases;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.isDynamicJdbcEnabled;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DynamicJdbcPropertyUtils} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringJUnitConfig(classes = DynamicJdbcPropertyUtilsTest.class)
@TestPropertySource(locations = "classpath:/META-INF/dynamic-jdbc/default.properties",
        properties = {"polydata.jdbc.modules.datasource.default-user-name=root",
                "polydata.jdbc.modules.datasource.default-password=123456"})
class DynamicJdbcPropertyUtilsTest {

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void testIsDynamicJdbcEnabled() {
        assertTrue(isDynamicJdbcEnabled(environment));
    }

    @Test
    void testGetAllDynamicJdbcModulesAutoConfigurationBasePackages() {
        Set<String> basePackages = getAllModulesAutoConfigurationBasePackages(environment);
        assertEquals(new TreeSet<>(asList(
                "com.baomidou.",
                "org.apache.shardingsphere.",
                "org.mybatis.spring.boot.",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceConfiguration",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
                "org.springframework.boot.autoconfigure.transaction.")), basePackages);
    }

    @Test
    void testGetModuleAutoConfigurationBasePackages() {
        Set<String> basePackages = getModuleAutoConfigurationBasePackages(environment, DATASOURCE_MODULE);
        assertEquals(new TreeSet<>(asList("org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceConfiguration")), basePackages);

        basePackages = getModuleAutoConfigurationBasePackages(environment, HIGH_AVAILABILITY_DATASOURCE_MODULE);
        assertEquals(emptySet(), basePackages);

        basePackages = getModuleAutoConfigurationBasePackages(environment, TRANSACTION_MODULE);
        assertEquals(ofSet("org.springframework.boot.autoconfigure.transaction.",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"), basePackages);

        basePackages = getModuleAutoConfigurationBasePackages(environment, SHARDING_SPHERE_MODULE);
        assertEquals(singleton("org.apache.shardingsphere."), basePackages);

        basePackages = getModuleAutoConfigurationBasePackages(environment, MYBATIS_MODULE);
        assertEquals(singleton("org.mybatis.spring.boot."), basePackages);

        basePackages = getModuleAutoConfigurationBasePackages(environment, MYBATIS_PLUS_MODULE);
        assertEquals(singleton("com.baomidou."), basePackages);
    }

    @Test
    void testGetDynamicJdbcModuleAutoConfigurationBannedModules() {
        Set<String> bannedModules = getModuleAutoConfigurationBannedModules(environment, DATASOURCE_MODULE);
        assertEquals(singleton("sharding-sphere"), bannedModules);

        bannedModules = getModuleAutoConfigurationBannedModules(environment, HIGH_AVAILABILITY_DATASOURCE_MODULE);
        assertEquals(singleton("sharding-sphere"), bannedModules);

        bannedModules = getModuleAutoConfigurationBannedModules(environment, TRANSACTION_MODULE);
        assertEquals(emptySet(), bannedModules);

        bannedModules = getModuleAutoConfigurationBannedModules(environment, SHARDING_SPHERE_MODULE);
        assertEquals(ofSet("datasource"), bannedModules);

        bannedModules = getModuleAutoConfigurationBannedModules(environment, MYBATIS_MODULE);
        assertEquals(singleton("mybatis-plus"), bannedModules);

        bannedModules = getModuleAutoConfigurationBannedModules(environment, MYBATIS_PLUS_MODULE);
        assertEquals(singleton("mybatis"), bannedModules);
    }

    @Test
    void testGetModuleExclusionAutoConfigurationClassNames() {
        // Since ShardingSphere JDBC 5.5.1 is used without the spring-boot starter, no ShardingSphere
        // auto-configuration class can be found on the classpath
        Set<String> exclusionClassNames = getModuleExclusionAutoConfigurationClassNames(context, DATASOURCE_MODULE);
        assertEquals(emptySet(), exclusionClassNames);

        exclusionClassNames = getModuleExclusionAutoConfigurationClassNames(context, HIGH_AVAILABILITY_DATASOURCE_MODULE);
        assertEquals(emptySet(), exclusionClassNames);

        exclusionClassNames = getModuleExclusionAutoConfigurationClassNames(context, TRANSACTION_MODULE);
        assertEquals(emptySet(), exclusionClassNames);

        exclusionClassNames = getModuleExclusionAutoConfigurationClassNames(context, SHARDING_SPHERE_MODULE);
        assertEquals(ofSet("org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"), exclusionClassNames);

        exclusionClassNames = getModuleExclusionAutoConfigurationClassNames(context, MYBATIS_MODULE);
        assertEquals(new TreeSet<>(asList("com.baomidou.mybatisplus.autoconfigure.DdlAutoConfiguration",
                "com.baomidou.mybatisplus.autoconfigure.IdentifierGeneratorAutoConfiguration",
                "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
                "com.baomidou.mybatisplus.autoconfigure.MybatisPlusInnerInterceptorAutoConfiguration",
                "com.baomidou.mybatisplus.autoconfigure.MybatisPlusLanguageDriverAutoConfiguration")), exclusionClassNames);

        exclusionClassNames = getModuleExclusionAutoConfigurationClassNames(context, MYBATIS_PLUS_MODULE);
        assertEquals(new TreeSet<>(asList("org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
                "org.mybatis.spring.boot.autoconfigure.MybatisLanguageDriverAutoConfiguration")), exclusionClassNames);
    }

    @Test
    void testGetModulePropertyNameAliases() {
        Set<String> propertyNameAliases = getModulePropertyNameAliases(environment, DATASOURCE_MODULE, "url");
        assertEquals(new TreeSet<>(asList("jdbcUrl", "jdbc-url")), propertyNameAliases);

        propertyNameAliases = getModulePropertyNameAliases(environment, DATASOURCE_MODULE, "jdbcUrl");
        assertEquals(new TreeSet<>(asList("url", "jdbc-url")), propertyNameAliases);

        propertyNameAliases = getModulePropertyNameAliases(environment, DATASOURCE_MODULE, "jdbc-url");
        assertEquals(new TreeSet<>(asList("url", "jdbcUrl")), propertyNameAliases);
    }

    @Test
    void testGetDataSourceUrlDefaultQueryParams() {
        Map<String, String> defaultQueryParams = getDataSourceUrlDefaultQueryParams(environment);
        assertEquals("utf-8", defaultQueryParams.get("characterEncoding"));
        assertEquals("false", defaultQueryParams.get("useSSL"));
        assertEquals("true", defaultQueryParams.get("useUnicode"));
    }

    @Test
    void testGetDataSourceDefaultUserName() {
        String defaultUserName = getDataSourceDefaultUserName(environment);
        assertEquals("root", defaultUserName);
    }

    @Test
    void testGetDataSourceDefaultPassword() {
        String defaultUserName = getDataSourceDefaultPassword(environment);
        assertEquals("123456", defaultUserName);
    }

    @Test
    void testGetDynamicJdbcModuleProperties() {
        Map<String, Object> moduleProperties = DynamicJdbcPropertyUtils.getModuleProperties(environment, new DynamicJdbcConfig.Mybatis());
        assertNotNull(moduleProperties);
        assertEquals("org.mybatis.spring.boot.", moduleProperties.get(AUTO_CONFIGURATION_BASE_PACKAGES_PROPERTY_NAME));
    }

    @Test
    void testGetDynamicJdbcModuleProperty() {
        String propertyValue = DynamicJdbcPropertyUtils.getModuleProperty(environment, new DynamicJdbcConfig.ShardingSphere(),
                AUTO_CONFIGURATION_BASE_PACKAGES_PROPERTY_NAME);
        assertEquals("org.apache.shardingsphere.", propertyValue);
    }

    @Test
    void testGetAllDynamicJdbcModulesPropertyValues() {
        List<String> propertyValues = getAllModulesPropertyValues(environment, AUTO_CONFIGURATION_BASE_PACKAGES_PROPERTY_NAME);
        assertEquals(asList(
                "com.baomidou.",
                "org.apache.shardingsphere.",
                "org.mybatis.spring.boot.",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceConfiguration",
                "org.springframework.boot.autoconfigure.transaction.,org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"), propertyValues);
    }

    @Test
    void testAppendPropertyValue() {
        Map<String, Object> properties = new HashMap<>();
        appendPropertyValue(properties, "test-name", "test-value");
        assertEquals("test-value", properties.get("test-name"));
        appendPropertyValue(properties, "test-name", "test-value-1");
        assertEquals("test-value,test-value-1", properties.get("test-name"));
    }

    @Test
    void testGetClusterDataSourceUserName() {
        MockEnvironment environment = new MockEnvironment();
        assertNull(getClusterDataSourceUserName(environment, null));
        assertNull(getClusterDataSourceUserName(environment, " "));
        assertNull(getClusterDataSourceUserName(environment, ""));
        assertNull(getClusterDataSourceUserName(environment, "clusterId"));

        environment.setProperty("spring.application.name", "test-app");
        assertNull(getClusterDataSourceUserName(environment, "clusterId"));

        environment.setProperty("test-app.clusterId.username", "root");
        assertEquals("root", getClusterDataSourceUserName(environment, "clusterId"));
    }

    @Test
    void testGetClusterDataSourcePassword() {
        MockEnvironment environment = new MockEnvironment();
        assertNull(getClusterDataSourcePassword(environment, null));
        assertNull(getClusterDataSourcePassword(environment, " "));
        assertNull(getClusterDataSourcePassword(environment, ""));
        assertNull(getClusterDataSourcePassword(environment, "clusterId"));

        environment.setProperty("spring.application.name", "test-app");
        assertNull(getClusterDataSourcePassword(environment, "clusterId"));

        environment.setProperty("test-app.clusterId.password", "1234560");
        assertEquals("1234560", getClusterDataSourcePassword(environment, "clusterId"));
    }
}
