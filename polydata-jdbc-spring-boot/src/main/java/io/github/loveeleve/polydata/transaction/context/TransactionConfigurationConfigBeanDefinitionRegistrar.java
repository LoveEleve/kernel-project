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
package io.github.loveeleve.polydata.transaction.context;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.context.AbstractConfigurationConfigBeanDefinitionRegistrar;
import io.github.loveeleve.polydata.context.ConfigBeanDefinitionRegistrar;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

import static org.springframework.util.StringUtils.commaDelimitedListToStringArray;

/**
 * {@link ConfigBeanDefinitionRegistrar} for Transaction
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class TransactionConfigurationConfigBeanDefinitionRegistrar
        extends AbstractConfigurationConfigBeanDefinitionRegistrar<DynamicJdbcConfig.Transaction> {

    @Override
    protected void register(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module,
                            DynamicJdbcConfig.Transaction transaction, BeanDefinitionRegistry registry) {
        registerAlias(transaction);
        registerCustomizers(dynamicJdbcConfig, module, transaction, registry);
    }

    private void registerAlias(DynamicJdbcConfig.Transaction transaction) {
        String name = transaction.getName();
        if (StringUtils.hasText(name)) {
            String[] aliases = commaDelimitedListToStringArray(name);
            registerBeanAliases(PlatformTransactionManager.class, aliases);
        }
    }

    private void registerCustomizers(DynamicJdbcConfig dynamicJdbcConfig, String module, DynamicJdbcConfig.Transaction transaction,
                                     BeanDefinitionRegistry registry) {
        String customizers = transaction.getCustomizers();
        if (!StringUtils.hasText(customizers)) {
            logger.info("No '{}' modules' PlatformTransactionManagerCustomizer Config of DynamicJdbcConfig[name :{}] to register BeanDefinitions",
                    module, dynamicJdbcConfig.getName());
            return;
        }
        registerConfigurationClasses(customizers, registry);
    }
}
