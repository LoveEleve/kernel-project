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
package io.github.loveeleve.polydata.context;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

import static io.github.loveeleve.polydata.support.AnnotatedBeanDefinitionRegistryUtils.scanBasePackages;

/**
 * Scanned-feature {@link AbstractConfigurationConfigBeanDefinitionRegistrar}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class AbstractScannedConfigurationConfigBeanDefinitionRegistrar<C extends DynamicJdbcConfig.Config>
        extends AbstractConfigurationConfigBeanDefinitionRegistrar<C> {

    @Override
    protected final void register(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module, C configuration,
                                  BeanDefinitionRegistry registry) {
        String basePackages = getBasePackages(configuration);
        if (shouldScanBasePackages()) {
            scanBasePackages(registry, basePackages);
        }
        register(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, module, configuration, basePackages, registry);
    }

    protected boolean shouldScanBasePackages() {
        return true;
    }

    protected abstract String getBasePackages(C configuration);

    protected abstract void register(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module, C configuration,
                                     String basePackages, BeanDefinitionRegistry registry);

}
