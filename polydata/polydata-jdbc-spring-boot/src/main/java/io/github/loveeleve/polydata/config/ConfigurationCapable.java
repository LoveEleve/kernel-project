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
import org.springframework.util.ReflectionUtils;

import static io.github.loveeleve.polydata.support.TypeUtils.resolveActualTypeArgumentClass;


/**
 * The Capable interface of {@link DynamicJdbcConfig.Config}
 *
 * @param <C> the type of {@link DynamicJdbcConfig.Config}
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public interface ConfigurationCapable<C extends DynamicJdbcConfig.Config> extends ModuleCapable {

    @SuppressWarnings("unchecked")
    default Class<C> getConfigurationClass() {
        return (Class<C>) resolveActualTypeArgumentClass(getClass(), ConfigurationCapable.class, 0);
    }

    default C getConfiguration(DynamicJdbcConfig dynamicJdbcConfig) {
        Object[] container = new Object[1];
        ReflectionUtils.doWithMethods(DynamicJdbcConfig.class, method -> {
            ReflectionUtils.makeAccessible(method);
            container[0] = ReflectionUtils.invokeMethod(method, dynamicJdbcConfig);
        }, method -> method.getName().startsWith("get") && method.getParameterCount() == 0 && method.getReturnType().equals(getConfigurationClass()));

        return (C) container[0];
    }

    default String getModule() {
        return DynamicJdbcConfigUtils.getModule(getConfigurationClass());
    }

}
