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
package io.github.loveeleve.polydata.mybatisplus.context;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.context.AbstractScannedConfigurationConfigBeanDefinitionRegistrar;
import io.github.loveeleve.polydata.context.ConfigBeanDefinitionRegistrar;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

import static io.github.loveeleve.polydata.support.AnnotatedBeanDefinitionRegistryUtils.registerBeans;

/**
 * {@link ConfigBeanDefinitionRegistrar} for Mybatis plus
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class MybatisPlusConfigurationConfigBeanDefinitionRegistrar
    extends AbstractScannedConfigurationConfigBeanDefinitionRegistrar<DynamicJdbcConfig.MybatisPlus> {

    @Override
    protected String getBasePackages(DynamicJdbcConfig.MybatisPlus mybatisPlus) {
        return mybatisPlus.getBasePackages();
    }

    @Override
    protected void register(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module,
                            DynamicJdbcConfig.MybatisPlus mybatisPlus, String basePackages, BeanDefinitionRegistry registry) {
        registerBeans(registry, MybatisPlusMapperScanConfiguration.class);
    }
}
