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

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.util.StringUtils;

/**
 * The utilities class for Annotated {@link org.springframework.beans.factory.config.BeanDefinition
 * BeanDefinition}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class AnnotatedBeanDefinitionRegistryUtils {

    private static final String PACKAGE_SEPARATOR = ",";

    private AnnotatedBeanDefinitionRegistryUtils() {
    }

    /**
     * Register the specified classes as annotated BeanDefinitions.
     *
     * @param registry  the {@link BeanDefinitionRegistry}
     * @param classes   the classes to register
     */
    public static void registerBeans(BeanDefinitionRegistry registry, Class<?>... classes) {
        if (classes == null || classes.length == 0) {
            return;
        }
        new AnnotatedBeanDefinitionReader(registry).register(classes);
    }

    /**
     * Scan the specified base packages and register the annotated BeanDefinitions.
     *
     * @param registry      the {@link BeanDefinitionRegistry}
     * @param basePackages  the comma delimited base packages
     */
    public static void scanBasePackages(BeanDefinitionRegistry registry, String... basePackages) {
        if (basePackages == null || basePackages.length == 0) {
            return;
        }
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(registry);
        for (String basePackage : basePackages) {
            if (!StringUtils.hasText(basePackage)) {
                continue;
            }
            String[] packages = org.springframework.util.StringUtils.tokenizeToStringArray(basePackage, PACKAGE_SEPARATOR);
            for (String pkg : packages) {
                if (StringUtils.hasText(pkg)) {
                    scanner.scan(pkg);
                }
            }
        }
    }
}
