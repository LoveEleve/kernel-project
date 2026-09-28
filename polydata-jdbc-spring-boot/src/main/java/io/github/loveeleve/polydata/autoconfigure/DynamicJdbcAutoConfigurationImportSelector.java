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
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationImportSelector;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ObjectUtils;

import java.util.Arrays;
import java.util.List;

import static io.github.loveeleve.polydata.autoconfigure.DynamicJdbcAutoConfigurationRepository.getAutoConfigurationClassNames;

/**
 * {@link ImportSelector} implementation imports the Auto-Configuration classes that the Dynamic JDBC requires
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 * @see DynamicJdbcAutoConfigurationRepository
 * @see DynamicJdbcAutoConfigurationImportFilter
 * @see DynamicJdbcAutoConfigurationImportListener
 * 
 */
class DynamicJdbcAutoConfigurationImportSelector extends AutoConfigurationImportSelector implements ImportSelector, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(DynamicJdbcAutoConfigurationImportSelector.class);

    private static final List<AutoConfigurationImportFilter> FILTERS = Arrays.asList(DynamicJdbcAutoConfigurationImportFilter.INSTANCE);

    @Override
    public String[] selectImports(AnnotationMetadata annotationMetadata) {
        ClassLoader classLoader = getBeanClassLoader();
        String[] autoConfigurationClassNames = getAutoConfigurationClassNames(classLoader);

        if (ObjectUtils.isEmpty(autoConfigurationClassNames)) {
            logger.debug("The cache of Dynamic JDBC Auto-Configuration Class names is missing, attempt to reselect...");
            // Or execute AutoConfigurationImportSelector#selectImports,
            // it indicates that DynamicJdbcAutoConfigurationImportListener will be processed,
            // adn than DynamicJdbcAutoConfigurationRepository will be refreshed
            super.selectImports(annotationMetadata);
            // Get the cached result again
            autoConfigurationClassNames = getAutoConfigurationClassNames(classLoader);
        } else {
            // Return the cached result after listening on DynamicJdbcAutoConfigurationImportListener
            return autoConfigurationClassNames;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("JDBC Auto-Configuration Class names : {}", Arrays.asList(autoConfigurationClassNames));
        }

        return autoConfigurationClassNames;
    }

    protected Class<?> getAnnotationClass() {
        return EnableDynamicJdbcAutoConfiguration.class;
    }

    protected List<AutoConfigurationImportFilter> getAutoConfigurationImportFilters() {
        return FILTERS;
    }

    @Override
    public void destroy() throws Exception {
        // Clear DynamicJdbcAutoConfigurationRepository
        DynamicJdbcAutoConfigurationRepository.clear();
    }
}
