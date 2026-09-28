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
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import static java.util.Collections.singletonMap;
import static io.github.loveeleve.polydata.support.SpringBootPropertyConstants.SPRING_AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME;

/**
 * The configurable {@link org.springframework.boot.autoconfigure.AutoConfigurationImportFilter}
 * utilities.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class ConfigurableAutoConfigurationImportFilter {

    /**
     * The property name that Dynamic JDBC synthesizes the auto-configuration class names to be
     * excluded : "polydata.autoconfigure.exclude"
     */
    public static final String AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME = "polydata.autoconfigure.exclude";

    /**
     * The name of the {@link MapPropertySource} that carries the effective
     * {@value SpringBootPropertyConstants#SPRING_AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME} property
     */
    public static final String AUTO_CONFIGURE_EXCLUDE_PROPERTY_SOURCE_NAME = "polydata-exclude";

    private ConfigurableAutoConfigurationImportFilter() {
    }

    /**
     * Add the auto-configuration classes to be excluded to the {@link Environment}.
     *
     * @param environment the {@link Environment}
     * @param classNames  the auto-configuration class names
     */
    public static void addExcludedAutoConfigurationClasses(Environment environment, String... classNames) {
        if (classNames == null || classNames.length == 0) {
            return;
        }
        addExcludedAutoConfigurationClasses(environment, Arrays.asList(classNames));
    }

    /**
     * Add the auto-configuration classes to be excluded to the {@link Environment}.
     *
     * <p>
     * The specified class names are merged with the current value of
     * {@value SpringBootPropertyConstants#SPRING_AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME} and the
     * {@value #AUTO_CONFIGURE_EXCLUDE_PROPERTY_SOURCE_NAME} {@link MapPropertySource} is added
     * first.
     *
     * @param environment the {@link Environment}
     * @param classNames  the auto-configuration class names
     */
    public static void addExcludedAutoConfigurationClasses(Environment environment, Collection<String> classNames) {
        if (classNames == null || classNames.isEmpty()) {
            return;
        }

        Set<String> excludedClassNames = new LinkedHashSet<>();

        String currentValue = environment.getProperty(SPRING_AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME);
        if (StringUtils.hasText(currentValue)) {
            excludedClassNames.addAll(Arrays.asList(StringUtils.commaDelimitedListToStringArray(currentValue)));
        }

        for (String className : classNames) {
            if (StringUtils.hasText(className)) {
                excludedClassNames.add(environment.resolvePlaceholders(className).trim());
            }
        }

        if (excludedClassNames.isEmpty()) {
            return;
        }

        MapPropertySource propertySource = new MapPropertySource(AUTO_CONFIGURE_EXCLUDE_PROPERTY_SOURCE_NAME,
                singletonMap(SPRING_AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME,
                        StringUtils.collectionToCommaDelimitedString(excludedClassNames)));

        if (environment instanceof ConfigurableEnvironment) {
            MutablePropertySources propertySources = ((ConfigurableEnvironment) environment).getPropertySources();
            if (propertySources.contains(AUTO_CONFIGURE_EXCLUDE_PROPERTY_SOURCE_NAME)) {
                propertySources.replace(AUTO_CONFIGURE_EXCLUDE_PROPERTY_SOURCE_NAME, propertySource);
            } else {
                propertySources.addFirst(propertySource);
            }
        }
    }
}
