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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.util.Set;

import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getAllModulesAutoConfigurationBasePackages;
import static org.springframework.util.ClassUtils.isPresent;

/**
 * {@link AutoConfigurationImportFilter} filters Dynamic JDBC Auto-Configuration Class names.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see DynamicJdbcAutoConfigurationImportListener
 * @see DynamicJdbcAutoConfigurationImportSelector
 * @since 1.0.0
 */
class DynamicJdbcAutoConfigurationImportFilter implements AutoConfigurationImportFilter, EnvironmentAware, BeanClassLoaderAware {

    public static final DynamicJdbcAutoConfigurationImportFilter INSTANCE = new DynamicJdbcAutoConfigurationImportFilter();

    private static final Logger logger = LoggerFactory.getLogger(DynamicJdbcAutoConfigurationImportFilter.class);

    private Set<String> basePackages;

    private ClassLoader classLoader;

    private DynamicJdbcAutoConfigurationImportFilter() {
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata) {
        int length = autoConfigurationClasses.length;
        boolean[] result = new boolean[length];
        for (int i = 0; i < result.length; i++) {
            String autoConfigurationClassName = autoConfigurationClasses[i];
            result[i] = match(autoConfigurationClassName) && isClassPresent(autoConfigurationClassName);
        }
        return result;
    }

    public boolean match(String autoConfigurationClassName) {
        for (String basePackage : basePackages) {
            if (autoConfigurationClassName.startsWith(basePackage)) {
                logger.debug("Dynamic JDBC Auto-Configuration Class[name : {}] was matched on base-package: '{}'",
                        autoConfigurationClassName, basePackage);
                return true;
            }
        }
        return false;
    }

    private boolean isClassPresent(String autoConfigurationClassName) {
        boolean present = isPresent(autoConfigurationClassName, classLoader);
        logger.debug("Dynamic JDBC Auto-Configuration Class[name : {}] isPresent : {}", autoConfigurationClassName, present);
        return present;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.basePackages = getAllModulesAutoConfigurationBasePackages((ConfigurableEnvironment) environment);
        logger.debug("Dynamic JDBC Auto-Configuration base-packages : {}", basePackages);
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }
}
