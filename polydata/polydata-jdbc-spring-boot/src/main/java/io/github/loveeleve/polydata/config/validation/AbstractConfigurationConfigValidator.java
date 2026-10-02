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
package io.github.loveeleve.polydata.config.validation;

import io.github.loveeleve.polydata.config.ConfigurationCapable;
import io.github.loveeleve.polydata.config.DynamicJdbcConfig;

import java.util.Map;

import static org.springframework.util.ClassUtils.isAssignable;
import static org.springframework.util.ClassUtils.isPresent;
import static org.springframework.util.ClassUtils.resolveClassName;
import static org.springframework.util.StringUtils.commaDelimitedListToStringArray;

/**
 * Abstract {@link ConfigValidator} Class for {@link DynamicJdbcConfig.Config}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ConfigValidator
 * @see DynamicJdbcConfig.Config
 * @since 1.0.0
 */
public abstract class AbstractConfigurationConfigValidator<C extends DynamicJdbcConfig.Config> extends AbstractConfigValidator
        implements ConfigurationCapable<C> {

    @Override
    public final void validate(DynamicJdbcConfig dynamicJdbcConfig, String shardingJdbConfigPropertyName, ValidationErrors errors) {
        C configuration = getConfiguration(dynamicJdbcConfig);
        if (configuration == null) {
            logger.info("No '{}' modules' Config of DynamicJdbcConfig[name :{}] to be validated", getModule(), dynamicJdbcConfig.getName());
            return;
        }
        validateConfiguration(dynamicJdbcConfig, configuration, errors);
        doValidate(dynamicJdbcConfig, configuration, errors);
    }

    private void validateConfiguration(DynamicJdbcConfig dynamicJdbcConfig, C configuration, ValidationErrors errors) {
        validateName(dynamicJdbcConfig, configuration.getName(), errors);
        validateConfigurations(dynamicJdbcConfig, configuration.getConfigurations(), errors);
        validateProperties(dynamicJdbcConfig, configuration.getProperties(), errors);
    }

    protected void validateName(DynamicJdbcConfig dynamicJdbcConfig, String name, ValidationErrors errors) {}

    protected void validateConfigurations(DynamicJdbcConfig dynamicJdbcConfig, String configurations, ValidationErrors errors) {
        validateComponentType(configurations, "configurations", Object.class, errors);
    }

    protected void validateProperties(DynamicJdbcConfig dynamicJdbcConfig, Map<String, Object> properties, ValidationErrors errors) {}

    protected abstract void doValidate(DynamicJdbcConfig dynamicJdbcConfig, C configuration, ValidationErrors errors);

    protected final void validateComponentType(String classValue, String propertyName, Class<?> targetType, ValidationErrors errors) {
        String[] classNames = commaDelimitedListToStringArray(classValue);
        for (String className : classNames) {
            if (!isPresent(className, classLoader)) {
                errors.addError("'{}' modules' '{}' property class '{}' can't be found", getModule(), propertyName, className);
            } else {
                Class<?> componentClass = resolveClassName(className, classLoader);
                if (!isAssignable(targetType, componentClass)) {
                    errors.addError("'{}' modules' '{}' property class '{}' is not the target type '{}'", getModule(), propertyName, className,
                            targetType.getName());
                }
            }
        }
    }


}
