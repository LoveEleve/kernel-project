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
package io.github.loveeleve.polyconfig.spring.env;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The utilities for loading and flattening resource based property sources.
 *
 * <p>
 * Two resource formats are supported:
 * <ul>
 *     <li>{@code .yml} / {@code .yaml} - loaded by {@link YamlPropertiesFactoryBean} (requires
 *     SnakeYAML on the classpath)</li>
 *     <li>everything else - loaded as {@link Properties} by {@link PropertiesLoaderUtils} with the
 *     given encoding</li>
 * </ul>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class ResourcePropertySources {

    private ResourcePropertySources() {
    }

    /**
     * Load the property sources of the given resource.
     *
     * @param name     the name of the property source
     * @param resource the resource
     * @param encoding the character encoding
     * @return the loaded property sources (never empty)
     * @throws IOException if the resource can't be read
     */
    public static List<PropertySource<?>> load(String name, Resource resource, String encoding) throws IOException {
        String fileName = resource.getFilename();
        if (fileName == null) {
            // e.g. ByteArrayResource from a configuration center, the resource name (dataId) is the
            // only source of the format information
            fileName = name;
        }
        if (fileName != null && (fileName.endsWith(".yml") || fileName.endsWith(".yaml"))) {
            return List.of(loadYaml(name, resource));
        }
        Properties properties = PropertiesLoaderUtils.loadProperties(new EncodedResource(resource, encoding));
        return List.of(new MapPropertySource(name, toMap(properties)));
    }

    private static PropertySource<?> loadYaml(String name, Resource resource) throws IOException {
        YamlPropertiesFactoryBean factoryBean = new YamlPropertiesFactoryBean();
        factoryBean.setResources(resource);
        try {
            Properties properties = factoryBean.getObject();
            return new MapPropertySource(name, properties == null ? Map.of() : toMap(properties));
        } catch (LinkageError e) {
            throw new IOException("YAML support requires SnakeYAML on the classpath", e);
        }
    }

    private static Map<String, Object> toMap(Properties properties) {
        Map<String, Object> map = new LinkedHashMap<>(properties.size());
        for (String propertyName : properties.stringPropertyNames()) {
            map.put(propertyName, properties.getProperty(propertyName));
        }
        return map;
    }

    /**
     * Flatten the enumerable properties of the given property sources.
     *
     * @param propertySources the property sources (may be {@code null})
     * @return the flattened properties, or an empty map when there is nothing enumerable
     */
    public static Map<String, Object> flatten(List<PropertySource<?>> propertySources) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (propertySources == null) {
            return properties;
        }
        for (PropertySource<?> propertySource : propertySources) {
            if (propertySource instanceof EnumerablePropertySource) {
                EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) propertySource;
                for (String propertyName : enumerable.getPropertyNames()) {
                    properties.putIfAbsent(propertyName, enumerable.getProperty(propertyName));
                }
            }
        }
        return properties;
    }
}
