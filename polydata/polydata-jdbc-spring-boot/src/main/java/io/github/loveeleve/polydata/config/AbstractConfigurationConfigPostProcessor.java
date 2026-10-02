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
package io.github.loveeleve.polydata.config;


import io.github.loveeleve.polydata.util.FunctionUtils;

/**
 * Abstract {@link ConfigPostProcessor} for {@link DynamicJdbcConfig.Config}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class AbstractConfigurationConfigPostProcessor<C extends DynamicJdbcConfig.Config> extends AbstractModuleConfigPostProcessor
        implements ConfigurationCapable<C> {

    protected final Class<?> configurationClass;

    public AbstractConfigurationConfigPostProcessor() {
        this.configurationClass = getConfigurationClass();
    }

    @Override
    protected boolean supports(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module) {
        C configuration = getConfiguration(dynamicJdbcConfig);

        if (configuration == null) {
            logger.info("No '{}' Configuration of DynamicJdbcConfig[name :{}] was configured", module, dynamicJdbcConfig.getName());
            return false;
        }

        return true;
    }

    @Override
    protected void postProcess(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module) {

        C configuration = getConfiguration(dynamicJdbcConfig);

        processName(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, module, configuration);

        postProcess(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, module, configuration);
    }

    protected void processName(DynamicJdbcConfig config, String dynamicJdbcConfigPropertyName, String module, C configuration) {
        logger.debug("The original name of {} Config : '{}' , DynamicJdbcConfig bean name : '{}'", module, configuration.getName(),
                dynamicJdbcConfigPropertyName);

        FunctionUtils.setIfAbsent(configuration::getName, () -> generateDefaultModuleName(config, module, configuration, dynamicJdbcConfigPropertyName),
                configuration::setName);

        logger.debug("The processed name of {} Config : '{}' , DynamicJdbcConfig bean name : '{}'", module, configuration.getName(),
                dynamicJdbcConfigPropertyName);
    }

    protected String generateDefaultModuleName(DynamicJdbcConfig dynamicJdbcConfig, String module, C configuration,
                                               String dynamicJdbcConfigPropertyName) {
        StringBuilder moduleNameBuilder = new StringBuilder(dynamicJdbcConfig.getName()).append(".").append(module);
        return moduleNameBuilder.toString();
    }

    protected abstract void postProcess(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module, C configuration);

}
