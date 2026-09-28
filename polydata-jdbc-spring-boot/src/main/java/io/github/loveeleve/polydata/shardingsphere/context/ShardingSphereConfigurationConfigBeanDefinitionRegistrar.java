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

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.context.AbstractConfigurationConfigBeanDefinitionRegistrar;
import io.github.loveeleve.polydata.context.ConfigBeanDefinitionRegistrar;
import io.github.loveeleve.polydata.datasource.constants.DataSourceConstants;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.SHARDING_SPHERE_MODULE;
import static io.github.loveeleve.polydata.shardingsphere.constants.ShardingSphereConstants.SHARDING_SPHERE_MODULE_DATASOURCE_DEFAULT_PROPERTIES_SUFFIX;
import static io.github.loveeleve.polydata.support.BeanRegistrar.registerBean;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getModuleDefaultProperties;
import static io.github.loveeleve.polydata.util.DynamicJdbcUtils.loadShardingSphereYamlResource;
import static org.springframework.util.ClassUtils.resolveClassName;

/**
 * {@link ConfigBeanDefinitionRegistrar} for ShardingSphere.
 *
 * <p>
 * The {@link DataSource} is built directly by
 * {@link YamlShardingSphereDataSourceFactory#createDataSource(Map, byte[])} from the datasource
 * properties of {@link DynamicJdbcConfig} and the ShardingSphere YAML configuration resource, then
 * registered in the child context. The {@code DynamicDataSource} delegates to this promoted
 * {@link DataSource}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class ShardingSphereConfigurationConfigBeanDefinitionRegistrar
        extends AbstractConfigurationConfigBeanDefinitionRegistrar<DynamicJdbcConfig.ShardingSphere> implements ResourceLoaderAware {

    /**
     * The suffix of the ShardingSphere {@link DataSource} Bean name
     */
    public static final String DATA_SOURCE_BEAN_NAME_SUFFIX = ".shardingSphereDataSource";

    private static final String URL_PROPERTY_NAME = DataSourceConstants.URL_PROPERTY_NAME;

    private static final String JDBC_URL_PROPERTY_NAME = "jdbcUrl";

    private ResourceLoader resourceLoader;

    @Override
    protected void register(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module,
                            DynamicJdbcConfig.ShardingSphere configuration, BeanDefinitionRegistry registry) {
        String configResource = configuration.getConfigResource();

        byte[] yamlContent = loadShardingSphereYamlResource(resourceLoader, configResource);

        Map<String, DataSource> dataSourceMap = new LinkedHashMap<>();

        try {
            createDataSourceMap(dynamicJdbcConfig, dataSourceMap);

            DataSource dataSource = createShardingSphereDataSource(dataSourceMap, yamlContent, configResource);

            String beanName = getDataSourceBeanName(configuration);

            registerBean(registry, beanName, dataSource);

            logger.info("ShardingSphere[name : {}] DataSource Bean[name : '{}'] was registered from the YAML Config Resource[location : {}]",
                    configuration.getName(), beanName, configResource);
        } catch (RuntimeException e) {
            // Avoid the physical DataSource(s) leak when the ShardingSphere DataSource can't be
            // created (e.g. an invalid YAML configuration or an invalid datasource property)
            closeQuietly(dataSourceMap);
            throw e;
        }
    }

    private void closeQuietly(Map<String, DataSource> dataSourceMap) {
        dataSourceMap.values().forEach(dataSource -> {
            if (dataSource instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) dataSource).close();
                } catch (Exception e) {
                    logger.warn("The DataSource[{}] can't be closed", dataSource, e);
                }
            }
        });
    }

    private DataSource createShardingSphereDataSource(Map<String, DataSource> dataSourceMap, byte[] yamlContent,
                                                      String configResource) {
        try {
            return YamlShardingSphereDataSourceFactory.createDataSource(dataSourceMap, yamlContent);
        } catch (SQLException | IOException | RuntimeException e) {
            throw new IllegalStateException("The ShardingSphere DataSource can't be created from the YAML Config Resource[location : "
                    + configResource + "]", e);
        }
    }

    private void createDataSourceMap(DynamicJdbcConfig dynamicJdbcConfig, Map<String, DataSource> dataSourceMap) {
        Map<String, Map<String, String>> dataSourcePropertiesMap = dynamicJdbcConfig.getDataSourcePropertiesMap();
        dataSourcePropertiesMap.forEach((name, dataSourceProperties) -> {
            dataSourceMap.put(name, createDataSource(name, dataSourceProperties));
        });
    }

    private DataSource createDataSource(String name, Map<String, String> dataSourceProperties) {
        String type = dataSourceProperties.getOrDefault(DataSourceConstants.TYPE_PROPERTY_NAME,
                DataSourceConstants.DEFAULT_DATASOURCE_TYPE_NAME);

        Class<?> dataSourceClass = resolveClassName(type, classLoader);

        DataSource dataSource = (DataSource) org.springframework.beans.BeanUtils.instantiateClass(dataSourceClass);

        Map<String, String> mergedProperties = new LinkedHashMap<>(getDefaultDataSourceProperties());
        mergedProperties.putAll(dataSourceProperties);

        applyDataSourceProperties(dataSource, name, mergedProperties);

        return dataSource;
    }

    private Map<String, String> getDefaultDataSourceProperties() {
        Map<String, Object> defaultProperties = getModuleDefaultProperties((ConfigurableEnvironment) environment,
                SHARDING_SPHERE_MODULE, SHARDING_SPHERE_MODULE_DATASOURCE_DEFAULT_PROPERTIES_SUFFIX);
        Map<String, String> dataSourceDefaultProperties = new LinkedHashMap<>(defaultProperties.size());
        defaultProperties.forEach((propertyName, propertyValue) -> dataSourceDefaultProperties.put(propertyName,
                String.valueOf(propertyValue)));
        return dataSourceDefaultProperties;
    }

    private void applyDataSourceProperties(DataSource dataSource, String name, Map<String, String> dataSourceProperties) {
        BeanWrapper beanWrapper = new BeanWrapperImpl(dataSource);
        dataSourceProperties.forEach((propertyName, propertyValue) -> {
            if (DataSourceConstants.NAME_PROPERTY_NAME.equals(propertyName)
                    || DataSourceConstants.TYPE_PROPERTY_NAME.equals(propertyName)) {
                return;
            }
            String targetPropertyName = URL_PROPERTY_NAME.equals(propertyName) ? JDBC_URL_PROPERTY_NAME : propertyName;
            if (beanWrapper.isWritableProperty(targetPropertyName)) {
                beanWrapper.setPropertyValue(targetPropertyName, propertyValue);
            } else {
                logger.debug("The property[name : '{}'] of DataSource[name : '{}'] can't be written", propertyName, name);
            }
        });
    }

    private String getDataSourceBeanName(DynamicJdbcConfig.ShardingSphere configuration) {
        String name = configuration.getName();
        return StringUtils.hasText(name) ? name + DATA_SOURCE_BEAN_NAME_SUFFIX
                : DynamicJdbcConfig.ShardingSphere.class.getSimpleName() + DATA_SOURCE_BEAN_NAME_SUFFIX;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }
}
