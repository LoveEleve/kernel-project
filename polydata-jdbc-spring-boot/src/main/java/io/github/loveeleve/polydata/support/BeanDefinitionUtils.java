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

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The utilities class for {@link BeanDefinition}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class BeanDefinitionUtils {

    private BeanDefinitionUtils() {
    }

    /**
     * Find the names of the infrastructure BeanDefinitions from the given
     * {@link ConfigurableListableBeanFactory}.
     *
     * @param beanFactory the {@link ConfigurableListableBeanFactory}
     * @return the names of infrastructure BeanDefinitions
     */
    public static Set<String> findInfrastructureBeanNames(ConfigurableListableBeanFactory beanFactory) {
        Set<String> infrastructureBeanNames = new LinkedHashSet<>();
        for (String beanDefinitionName : beanFactory.getBeanDefinitionNames()) {
            if (beanFactory.containsBeanDefinition(beanDefinitionName)) {
                BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanDefinitionName);
                if (beanDefinition.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE) {
                    infrastructureBeanNames.add(beanDefinitionName);
                }
            }
        }
        return infrastructureBeanNames;
    }
}
