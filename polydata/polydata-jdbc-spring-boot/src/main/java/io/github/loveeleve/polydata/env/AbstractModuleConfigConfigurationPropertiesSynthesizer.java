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

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.config.ModuleCapable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getModuleAutoConfigurationClassNames;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getModuleExclusionAutoConfigurationClassNames;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getModulePropertyNameAliases;
import static io.github.loveeleve.polydata.util.DynamicJdbcUtils.flatProperties;

/**
 * {@link AbstractConfigConfigurationPropertiesSynthesizer} for Module
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class AbstractModuleConfigConfigurationPropertiesSynthesizer extends AbstractConfigConfigurationPropertiesSynthesizer
        implements ModuleCapable {

    @Override
    public final void synthesize(DynamicJdbcConfig dynamicJdbcConfig, Map<String, Object> properties) {

        String module = getModule();

        Assert.notNull(module, () -> "getModule() method must not return null!");

        if (!supports(dynamicJdbcConfig, module, properties)) {
            logger.info("DynamicJdbcConfig[name : '{}'] module '{}' is not supported", dynamicJdbcConfig.getName(), module);
            // if (isDynamicDynamicJdbcConfig(dynamicJdbcConfig)) {
            excludeModuleAutoConfigurationProperty(module, properties);
            // }
            return;
        }

        logger.debug("DynamicJdbcConfig[name : {}] Module '{}' pre-synthesize properties {}", dynamicJdbcConfig.getName(), module, properties);
        synthesize(dynamicJdbcConfig, module, properties);
        logger.debug("DynamicJdbcConfig[name : {}] Module '{}' post-synthesize properties {}", dynamicJdbcConfig.getName(), module, properties);
    }

    private void excludeModuleAutoConfigurationProperty(String module, Map<String, Object> properties) {
        Set<String> moduleAutoConfigurationClassNames = getModuleAutoConfigurationClassNames(context, module);
        excludeAutoConfigurationProperty(properties, moduleAutoConfigurationClassNames);
    }

    protected boolean supports(DynamicJdbcConfig dynamicJdbcConfig, String module, Map<String, Object> properties) {
        return true;
    }

    protected abstract void synthesize(DynamicJdbcConfig dynamicJdbcConfig, String module, Map<String, Object> properties);

    protected final void synthesizeModuleExclusionAutoConfigurationProperty(String module, Map<String, Object> properties) {
        Set<String> exclusionAutoConfigurationClassNames = getModuleExclusionAutoConfigurationClassNames(context, module);
        logger.debug("Module '{}' will append exclusion auto-configuration class names : {}", module, exclusionAutoConfigurationClassNames);
        excludeAutoConfigurationProperty(properties, exclusionAutoConfigurationClassNames);
    }

    protected void synthesizeConfigurationProperties(String module, Class<?> configurationPropertiesClass, Map<String, Object> sourceProperties,
                                                     Map<String, Object> properties) {
        if (CollectionUtils.isEmpty(sourceProperties)) {
            return;
        }

        String prefix = resolvePropertyNamePrefix(configurationPropertiesClass);

        synthesizeModuleProperties(module, sourceProperties, prefix, properties);
    }

    protected void synthesizeModuleProperties(String module, Map<String, Object> sourceProperties, String prefix, Map<String, Object> properties) {
        if (CollectionUtils.isEmpty(sourceProperties)) {
            logger.debug("No {} module property was configured", module);
            return;
        }

        Map<String, String> flattenProperties = flatProperties(sourceProperties);

        flattenProperties.forEach((propertyName, propertyValue) -> {
            Set<String> fullPropertyNames = resolveFullPropertyNames(module, prefix, propertyName);
            fullPropertyNames.forEach(fullPropertyName -> {
                if (filterModuleProperty(module, propertyName, fullPropertyName, propertyValue)) {
                    properties.put(fullPropertyName, propertyValue);
                    logger.debug("'{}' module synthesizes a new property [ source name : '{}'] : {} = {}", module, propertyName, fullPropertyName,
                            propertyValue);
                }
            });
        });
    }

    protected boolean filterModuleProperty(String module, String sourcePropertyName, String synthesizePropertyName, String propertyValue) {
        return true;
    }

    private Set<String> resolveFullPropertyNames(String module, String prefix, String name) {
        Set<String> aliases = getModulePropertyNameAliases(environment, module, name);
        Set<String> propertyNames = new HashSet<>(aliases.size() + 1);
        propertyNames.add(prefix + name);
        aliases.forEach(alias -> propertyNames.add(prefix + alias));
        return propertyNames;
    }

}
