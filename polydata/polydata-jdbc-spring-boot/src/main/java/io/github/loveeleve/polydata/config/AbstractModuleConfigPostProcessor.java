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

import org.springframework.util.Assert;

/**
 * Abstract {@link ConfigPostProcessor} for Module
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class AbstractModuleConfigPostProcessor extends AbstractConfigPostProcessor implements ModuleCapable {

    @Override
    public final void postProcess(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName) {

        String module = getModule();

        Assert.notNull(module, () -> "getModule() method must not return null!");

        if (!supports(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, module)) {
            logger.info("{} is not supported for DynamicJdbcConfig[name :{}] module[name : {}] ", getClass().getSimpleName(),
                    dynamicJdbcConfig.getName(), module);
            return;
        }

        postProcess(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, module);
    }

    /**
     * Supports to post-process or not
     *
     * @param dynamicJdbcConfig {@link DynamicJdbcConfig}
     * @param dynamicJdbcConfigPropertyName the property name of {@link DynamicJdbcConfig}
     * @param module the module, e.g "datasource" , "transaction" and so on.
     * @return if supports, return <code>true</code> as default, or <code>false</code>
     */
    protected boolean supports(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module) {
        return true;
    }

    /**
     * Post-Process {@link DynamicJdbcConfig} that may be modified in the specified implementation
     *
     * @param dynamicJdbcConfig {@link DynamicJdbcConfig}
     * @param dynamicJdbcConfigPropertyName the property name of {@link DynamicJdbcConfig}
     * @param module the module, e.g "datasource" , "transaction" and so on.
     */
    protected abstract void postProcess(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName, String module);


}
