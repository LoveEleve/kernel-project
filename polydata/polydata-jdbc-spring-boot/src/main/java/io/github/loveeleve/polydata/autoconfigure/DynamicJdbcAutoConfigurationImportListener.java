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
package io.github.loveeleve.polydata.autoconfigure;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.autoconfigure.AutoConfigurationImportEvent;
import org.springframework.boot.autoconfigure.AutoConfigurationImportListener;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Dynamic JDBC {@link AutoConfigurationImportListener} uses
 * {@link DynamicJdbcAutoConfigurationImportFilter} to reduce the duplicated loading calculation of
 * {@link DynamicJdbcAutoConfigurationImportSelector}.
 * 
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see DynamicJdbcAutoConfigurationImportFilter
 * @see DynamicJdbcAutoConfigurationImportSelector
 * @see DynamicJdbcAutoConfigurationRepository
 * @see EnableAutoConfiguration
 * @since 1.0.0
 */
public class DynamicJdbcAutoConfigurationImportListener implements AutoConfigurationImportListener, EnvironmentAware, BeanClassLoaderAware {

    private static final DynamicJdbcAutoConfigurationImportFilter filter = DynamicJdbcAutoConfigurationImportFilter.INSTANCE;

    private ClassLoader classLoader;

    private ConfigurableEnvironment environment;

    @Override
    public void onAutoConfigurationImportEvent(AutoConfigurationImportEvent event) {

        if (DynamicJdbcAutoConfigurationRepository.isCached(classLoader)) {
            return;
        }

        List<String> configurationClassNames = event.getCandidateConfigurations();
        String[] matchedConfigurationClassNames = configurationClassNames.stream().filter(filter::match).toArray(String[]::new);
        // Cache the Dynamic JDBC Auto-Configuration Class Names
        DynamicJdbcAutoConfigurationRepository.cache(classLoader, matchedConfigurationClassNames);
    }


    @Override
    public void setEnvironment(Environment environment) {
        this.environment = (ConfigurableEnvironment) environment;
        filter.setEnvironment(environment);
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }
}
