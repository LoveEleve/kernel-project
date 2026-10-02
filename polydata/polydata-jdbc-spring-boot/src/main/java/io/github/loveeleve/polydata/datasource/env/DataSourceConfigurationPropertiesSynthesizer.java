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
package io.github.loveeleve.polydata.datasource.env;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.env.AbstractModuleConfigConfigurationPropertiesSynthesizer;
import io.github.loveeleve.polydata.env.ConfigConfigurationPropertiesSynthesizer;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import java.beans.PropertyDescriptor;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.DATASOURCE_MODULE;
import static io.github.loveeleve.polydata.datasource.constants.DataSourceConstants.JDBC_URL_PROPERTY_NAMES;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getDataSourceType;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.joinPropertyName;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;

/**
 * {@link ConfigConfigurationPropertiesSynthesizer} for Single DataSource
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class DataSourceConfigurationPropertiesSynthesizer extends AbstractModuleConfigConfigurationPropertiesSynthesizer {

    private static final Map<String, String> dataSourcePropertiesPropertyNamePrefixes;

    private static final Set<String> dataSourcePropertiesPropertyNames;

    static {
        dataSourcePropertiesPropertyNamePrefixes = initDataSourcePropertiesPropertyNamePrefixes();
        dataSourcePropertiesPropertyNames = getDataSourcePropertiesPropertyNames();
    }

    @Override
    protected boolean supports(DynamicJdbcConfig dynamicJdbcConfig, String module, Map<String, Object> properties) {

        if (dynamicJdbcConfig.hasShardingDataSource()) {
            logger.info("Current DynamicJdbcConfig[{}] has Dynamic DataSource, thus BeanDefinition will be not be registered",
                    dynamicJdbcConfig.getName());
            return false;
        }

        return dynamicJdbcConfig.hasOnlySingleDataSource();
    }

    @Override
    protected void synthesize(DynamicJdbcConfig dynamicJdbcConfig, String module, Map<String, Object> properties) {
        List<Map<String, String>> zonePreferenceDataSourcePropertiesList = dynamicJdbcConfig.getDataSourcePropertiesList();
        Map dataSourceProperties = zonePreferenceDataSourcePropertiesList.get(0);
        synthesizeConfigurationProperties(module, DataSourceProperties.class, dataSourceProperties, properties);
        synthesizeDataSourceProperties(module, dataSourceProperties, properties);
        synthesizeModuleExclusionAutoConfigurationProperty(module, properties);
    }

    @Override
    protected boolean filterModuleProperty(String module, String sourcePropertyName, String synthesizePropertyName, String propertyValue) {
        return isDataSourcePropertiesPropertyName(sourcePropertyName);
    }

    private void synthesizeDataSourceProperties(String module, Map<String, String> dataSourceProperties, Map<String, Object> properties) {
        String prefix = getDataSourcePropertiesPropertyNamePrefix(dataSourceProperties);
        if (prefix != null) {
            dataSourceProperties.forEach((propertyName, propertyValue) -> {
                if (!isDataSourcePropertiesPropertyName(propertyName)) {
                    String fullPropertyName = joinPropertyName(prefix, propertyName);
                    properties.put(fullPropertyName, propertyValue);
                    logger.debug("'{}' module synthesizes a new DataSource property[ source name : '{}'] : {} = {}", module, propertyName,
                            fullPropertyName, propertyValue);
                }
            });
        }
    }

    @Override
    public String getModule() {
        return DATASOURCE_MODULE;
    }

    private static Map<String, String> initDataSourcePropertiesPropertyNamePrefixes() {
        Map<String, String> propertyNamePrefixes = new HashMap<>(4);
        propertyNamePrefixes.put("org.apache.tomcat.jdbc.pool.DataSource", "spring.datasource.tomcat");
        propertyNamePrefixes.put("com.zaxxer.hikari.HikariDataSource", "spring.datasource.hikari");
        propertyNamePrefixes.put("org.apache.commons.dbcp2.BasicDataSource", "spring.datasource.dbcp2");
        return unmodifiableMap(propertyNamePrefixes);
    }

    private static Set<String> getDataSourcePropertiesPropertyNames() {
        Set<String> propertyNames = new LinkedHashSet<>();
        BeanWrapperImpl beanWrapper = new BeanWrapperImpl(DataSourceProperties.class);
        for (PropertyDescriptor propertyDescriptor : beanWrapper.getPropertyDescriptors()) {
            String propertyName = propertyDescriptor.getName();
            propertyNames.add(propertyName);
        }
        propertyNames.addAll(asList(JDBC_URL_PROPERTY_NAMES));
        return unmodifiableSet(propertyNames);
    }

    private static String getDataSourcePropertiesPropertyNamePrefix(Map<String, String> dataSourceProperties) {
        String type = getDataSourceType(dataSourceProperties);
        return dataSourcePropertiesPropertyNamePrefixes.get(type);
    }

    private static boolean isDataSourcePropertiesPropertyName(String propertyName) {
        return dataSourcePropertiesPropertyNames.contains(propertyName);
    }
}
