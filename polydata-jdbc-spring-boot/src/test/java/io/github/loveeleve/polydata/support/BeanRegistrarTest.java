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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static io.github.loveeleve.polydata.support.BeanDefinitionUtils.findInfrastructureBeanNames;
import static io.github.loveeleve.polydata.support.BeanRegistrar.registerBean;
import static io.github.loveeleve.polydata.support.BeanRegistrar.registerFactoryBean;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BeanRegistrar}, {@link BeanDefinitionUtils} and
 * {@link ConfigurableAutoConfigurationImportFilter} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class BeanRegistrarTest {

    @Test
    void testRegisterBean() {
        GenericApplicationContext context = new GenericApplicationContext();
        String bean = new String("polydata");
        registerBean(context, "bean", bean);

        context.refresh();

        assertSame(bean, context.getBean("bean"));
        assertEquals(bean, context.getBean(String.class));
    }

    @Test
    void testRegisterFactoryBean() {
        GenericApplicationContext context = new GenericApplicationContext();
        Object bean = new Object();
        registerFactoryBean(context, "factoryBean", bean, true);

        context.refresh();

        assertSame(bean, context.getBean("factoryBean"));
        assertTrue(context.getBeanDefinition("factoryBean").isPrimary());
    }

    @Test
    void testFindInfrastructureBeanNames() {
        GenericApplicationContext context = new GenericApplicationContext();

        BeanDefinition applicationBeanDefinition = BeanDefinitionBuilder.genericBeanDefinition(String.class)
                .getBeanDefinition();
        context.registerBeanDefinition("applicationBean", applicationBeanDefinition);

        BeanDefinition infrastructureBeanDefinition = BeanDefinitionBuilder.genericBeanDefinition(String.class)
                .setRole(BeanDefinition.ROLE_INFRASTRUCTURE)
                .getBeanDefinition();
        context.registerBeanDefinition("infrastructureBean", infrastructureBeanDefinition);

        Set<String> infrastructureBeanNames = findInfrastructureBeanNames(context.getBeanFactory());

        assertEquals(1, infrastructureBeanNames.size());
        assertTrue(infrastructureBeanNames.contains("infrastructureBean"));
    }

    @Test
    void testAddExcludedAutoConfigurationClasses() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                SpringBootPropertyConstants.SPRING_AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME, "com.example.A")));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(SpringBootPropertyConstants.SPRING_AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME, "com.example.A");

        ConfigurableAutoConfigurationImportFilter.addExcludedAutoConfigurationClasses(context.getEnvironment(),
                "com.example.B", "${excluded.class:C}");

        String excluded = context.getEnvironment().getProperty(SpringBootPropertyConstants.SPRING_AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME);
        assertEquals("com.example.A,com.example.B,C", excluded);
    }
}
