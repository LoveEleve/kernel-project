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
import io.github.loveeleve.polydata.config.ModuleCapable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * {@link AbstractConfigBeanDefinitionRegistrar} for Module
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class AbstractModuleConfigBeanDefinitionRegistrar extends AbstractConfigBeanDefinitionRegistrar implements ModuleCapable {

    @Override
    public final void register(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, BeanDefinitionRegistry registry) {
        String module = getModule();

        Assert.notNull(module, () -> "getModule() method must not return null!");

        if (!supports(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, module, registry)) {
            logger.info("{} is not supported for DynamicJdbcConfig[name :{}] module[name : {}] ", getClass().getSimpleName(),
                    dynamicJdbcConfig.getName(), module);
            return;
        }

        register(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, module, registry);
    }

    protected final void registerBeanAliases(String beanName, String... aliases) {
        if (beanFactory instanceof BeanDefinitionRegistry) {
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
            for (String alias : aliases) {
                registry.registerAlias(beanName, alias);
                logger.debug("'{}' module register alias '{}' for bean name : '{}'", getModule(), alias, beanName);
            }
        }
    }

    protected final void registerBeanAliases(Class<?> beanType, String... aliases) {
        beanFactory.addBeanPostProcessor((MergedBeanDefinitionPostProcessor) (beanDefinition, type, beanName) -> {
            if (ClassUtils.isAssignable(beanType, type)) {
                registerBeanAliases(beanName, aliases);
            }
        });
    }

    protected boolean supports(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module,
                               BeanDefinitionRegistry registry) {
        return true;
    }

    protected abstract void register(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module,
                                     BeanDefinitionRegistry registry);
}
