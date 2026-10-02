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
package io.github.loveeleve.polydata.support;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.Environment;
import org.springframework.format.support.DefaultFormattingConversionService;

/**
 * The resolver of {@link ConversionService}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class ConversionServiceResolver {

    /**
     * The default Bean name of {@link ConversionService}
     */
    public static final String CONVERSION_SERVICE_BEAN_NAME = "conversionService";

    /**
     * The name of the resolved {@link ConversionService} singleton
     */
    public static final String RESOLVED_CONVERSION_SERVICE_BEAN_NAME = "resolved-conversionService";

    private final ConfigurableBeanFactory beanFactory;

    public ConversionServiceResolver(ConfigurableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * Resolve the {@link ConversionService} :
     * <ol>
     * <li>the Bean named "conversionService" if present</li>
     * <li>{@link ConfigurableBeanFactory#getConversionService()}</li>
     * <li>the {@link ConversionService} of the {@link Environment} Bean</li>
     * <li>a {@link DefaultFormattingConversionService}</li>
     * </ol>
     * The resolved {@link ConversionService} is registered as a singleton named
     * "resolved-conversionService" when absent.
     *
     * @return non-null
     */
    public ConversionService resolve() {
        ConversionService conversionService = null;

        if (beanFactory.containsBean(CONVERSION_SERVICE_BEAN_NAME)) {
            conversionService = beanFactory.getBean(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class);
        }

        if (conversionService == null) {
            conversionService = beanFactory.getConversionService();
        }

        if (conversionService == null) {
            Environment environment = beanFactory.getBeanProvider(Environment.class).getIfAvailable();
            if (environment != null) {
                conversionService = EnvironmentUtils.getConversionService(environment);
            }
        }

        if (conversionService == null) {
            conversionService = new DefaultFormattingConversionService();
        }

        if (!beanFactory.containsSingleton(RESOLVED_CONVERSION_SERVICE_BEAN_NAME)) {
            beanFactory.registerSingleton(RESOLVED_CONVERSION_SERVICE_BEAN_NAME, conversionService);
        }

        return conversionService;
    }
}
