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
package io.github.loveeleve.polydata.env;

import io.github.loveeleve.polydata.constants.DynamicJdbcConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.Properties;

/**
 * The {@link EnvironmentPostProcessor} that loads the Dynamic JDBC default properties from every
 * {@value #DEFAULT_PROPERTIES_LOCATION} resource and registers them as the lowest-priority
 * {@link PropertySource property sources}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class DynamicJdbcDefaultPropertiesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * The resource location pattern of Dynamic JDBC default properties
     */
    public static final String DEFAULT_PROPERTIES_LOCATION = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
            + DynamicJdbcConstants.DEFAULT_PROPERTIES_LOCATION;

    private static final String PROPERTY_SOURCE_NAME_PREFIX = "dynamic-jdbc-default-properties[";

    private static final Logger logger = LoggerFactory.getLogger(DynamicJdbcDefaultPropertiesEnvironmentPostProcessor.class);

    private final ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            Resource[] resources = resourcePatternResolver.getResources(DEFAULT_PROPERTIES_LOCATION);
            for (Resource resource : resources) {
                Properties defaultProperties = PropertiesLoaderUtils.loadProperties(resource);
                String propertySourceName = PROPERTY_SOURCE_NAME_PREFIX + resource.getDescription() + "]";
                environment.getPropertySources().addLast(new PropertiesPropertySource(propertySourceName, defaultProperties));
                logger.debug("Dynamic JDBC default properties[resource : {}] were loaded : {}",
                        resource.getDescription(), defaultProperties);
            }
        } catch (IOException e) {
            throw new IllegalStateException("The Dynamic JDBC default properties[location : " + DEFAULT_PROPERTIES_LOCATION
                    + "] can't be loaded", e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
