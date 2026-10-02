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

import io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils;
import io.github.loveeleve.polydata.util.FunctionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

import static io.github.loveeleve.polydata.support.BeanUtils.invokeAwareInterfaces;

/**
 * {@link DynamicJdbcConfig} {@link BeanPostProcessor}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class DynamicJdbcConfigPostProcessor extends AbstractConfigPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(DynamicJdbcConfigPostProcessor.class);

    @Override
    public void postProcess(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName) {
        processDynamicJdbcConfig(dynamicJdbcConfig, dynamicJdbcConfigPropertyName);
    }

    private void processDynamicJdbcConfig(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName) {
        processName(dynamicJdbcConfig, dynamicJdbcConfigPropertyName);
        invokeAwareInterfaces(dynamicJdbcConfig, context);
    }

    private void processName(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName) {
        String name = dynamicJdbcConfig.getName();
        logger.debug("The original name of DynamicJdbcConfig : {} , property name : {}", name, dynamicJdbcConfigPropertyName);
        FunctionUtils.setValueIfAbsent(name, () -> DynamicJdbcConfigUtils.getDynamicJdbcConfigPropertyNameSuffix(dynamicJdbcConfigPropertyName), dynamicJdbcConfig::setName);
        logger.debug("The processed name of DynamicJdbcConfig : {}", dynamicJdbcConfig.getName());
    }
}
