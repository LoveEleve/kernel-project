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

import io.github.loveeleve.polydata.support.ConversionServiceResolver;
import org.apache.commons.lang3.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionService;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The resolver to flat the configuration properties from the nested properties.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class ConfigurationPropertiesFlatter {

    private static final ConfigurationPropertiesFlatter instance = new ConfigurationPropertiesFlatter();

    private final Logger logger = LoggerFactory.getLogger(ConfigurationPropertiesFlatter.class);

    private final ConversionService conversionService;

    public ConfigurationPropertiesFlatter(BeanFactory beanFactory) {
        ConversionServiceResolver conversionServiceResolver = new ConversionServiceResolver((ConfigurableBeanFactory) beanFactory);
        this.conversionService = conversionServiceResolver.resolve();
    }

    public ConfigurationPropertiesFlatter(ConversionService conversionService) {
        this.conversionService = conversionService == null ? ApplicationConversionService.getSharedInstance() : conversionService;
    }

    public ConfigurationPropertiesFlatter() {
        this(ApplicationConversionService.getSharedInstance());
    }

    public static ConfigurationPropertiesFlatter getInstance() {
        return instance;
    }

    public Map<String, String> flat(Map<String, Object> nestedProperties) {
        Map<String, String> flattenProperties = new LinkedHashMap<>();
        if (nestedProperties != null) {
            for (Map.Entry<String, Object> entry : nestedProperties.entrySet()) {
                String propertyName = entry.getKey();
                Object propertyValue = entry.getValue();
                if (propertyValue != null) {
                    flat(propertyName, propertyValue, propertyValue.getClass(), flattenProperties);
                }
            }
        }
        return flattenProperties;
    }

    private void flat(String propertyName, Object propertyValue, Class<?> propertyValueType, Map<String, String> flattenProperties) {
        if (Iterable.class.isAssignableFrom(propertyValueType)) {
            Iterable elements = (Iterable) propertyValue;
            flatMultiple(propertyName, elements, flattenProperties);
        } else if (propertyValueType.isArray()) {
            Object[] elements = (Object[]) propertyValue;
            flatMultiple(propertyName, Arrays.asList(elements), flattenProperties);
        } else {
            flatSingle(propertyName, propertyValue, propertyValueType, flattenProperties);
        }
    }

    private void flatMultiple(String containerPropertyName, Iterable elements, Map<String, String> flattenProperties) {
        int index = 0;
        for (Object element : elements) {
            if (element == null) {
                logger.warn("The null element at index[{}] of the property [{}] will be ignored", index, containerPropertyName);
            } else {
                flat(containerPropertyName + "[" + index + "]", element, element.getClass(), flattenProperties);
            }
            index++;
        }
    }

    private void flatSingle(String propertyName, Object propertyValue, Class<?> propertyValueType, Map<String, String> flattenProperties) {
        if (Map.class.isAssignableFrom(propertyValueType)) { // Map type as nested
            Map<String, String> innerProperties = flat((Map<String, Object>) propertyValue);
            innerProperties.forEach((subPropertyName, subPropertyValue) -> {
                flattenProperties.put(propertyName + "." + subPropertyName, subPropertyValue);
            });
        } else if (isResolvableType(propertyValueType)) {
            flattenProperties.put(propertyName, String.valueOf(propertyValue));
        } else {
            logger.warn("The property [name : {} , value : {} , type : {}] can't be resolved!", propertyName, propertyValue, propertyValueType);
        }
    }

    private boolean isResolvableType(Class<?> type) {
        return ClassUtils.isPrimitiveOrWrapper(type) || conversionService.canConvert(String.class, type);
    }
}
