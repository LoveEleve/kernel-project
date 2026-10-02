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
package io.github.loveeleve.polydata.support;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The utilities class for {@link PropertySources}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class PropertySourcesUtils {

    private static final String PROPERTY_NAME_SEPARATOR = ".";

    private PropertySourcesUtils() {
    }

    /**
     * Normalize the property name prefix, append the {@code "."} when absent.
     *
     * @param prefix the property name prefix
     * @return the normalized prefix
     */
    public static String normalizePrefix(String prefix) {
        return prefix.endsWith(PROPERTY_NAME_SEPARATOR) ? prefix : prefix + PROPERTY_NAME_SEPARATOR;
    }

    /**
     * Get the sub properties of the given {@link PropertySources} by the specified prefix.
     *
     * @param propertySources the {@link PropertySources}
     * @param prefix          the property name prefix
     * @return the unmodifiable sub properties
     */
    public static Map<String, Object> getSubProperties(PropertySources propertySources, String prefix) {
        String normalizedPrefix = normalizePrefix(prefix);

        Map<String, Object> subProperties = new LinkedHashMap<>();

        PropertySourcesPropertyResolver propertyResolver = new PropertySourcesPropertyResolver(propertySources);

        for (PropertySource<?> propertySource : propertySources) {
            if (propertySource instanceof EnumerablePropertySource) {
                EnumerablePropertySource<?> enumerablePropertySource = (EnumerablePropertySource<?>) propertySource;
                for (String propertyName : enumerablePropertySource.getPropertyNames()) {
                    if (propertyName.startsWith(normalizedPrefix)) {
                        String subPropertyName = propertyName.substring(normalizedPrefix.length());
                        if (!subProperties.containsKey(subPropertyName)) {
                            Object propertyValue = propertyResolver.getProperty(propertyName, Object.class);
                            subProperties.put(subPropertyName, propertyValue);
                        }
                    }
                }
            }
        }

        return Collections.unmodifiableMap(subProperties);
    }

    /**
     * Get the sub properties of the given {@link Environment} by the specified prefix.
     *
     * @param environment the {@link Environment}
     * @param prefix      the property name prefix
     * @return the unmodifiable sub properties
     */
    public static Map<String, Object> getSubProperties(Environment environment, String prefix) {
        if (environment instanceof ConfigurableEnvironment) {
            return getSubProperties(((ConfigurableEnvironment) environment).getPropertySources(), prefix);
        }
        return Collections.emptyMap();
    }

    /**
     * Find the property names that start with the given prefix from the
     * {@link ConfigurableEnvironment}.
     *
     * @param environment the {@link ConfigurableEnvironment}
     * @param prefix      the property name prefix
     * @return the property names in the order of {@link PropertySources}
     */
    public static Set<String> findPropertyNamesByPrefix(ConfigurableEnvironment environment, String prefix) {
        Set<String> propertyNames = new LinkedHashSet<>();
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (propertySource instanceof EnumerablePropertySource) {
                for (String propertyName : ((EnumerablePropertySource<?>) propertySource).getPropertyNames()) {
                    if (propertyName.startsWith(prefix)) {
                        propertyNames.add(propertyName);
                    }
                }
            }
        }
        return propertyNames;
    }

    /**
     * Find the name of the first {@link PropertySource}, in the order of the
     * {@link ConfigurableEnvironment}, that contains the specified property.
     *
     * @param environment  the {@link ConfigurableEnvironment}
     * @param propertyName the property name
     * @return the name of the property source, or {@code null} if the property can't be found
     */
    public static String findConfiguredPropertySourceName(ConfigurableEnvironment environment, String propertyName) {
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (propertySource.getProperty(propertyName) != null) {
                return propertySource.getName();
            }
        }
        return null;
    }
}
