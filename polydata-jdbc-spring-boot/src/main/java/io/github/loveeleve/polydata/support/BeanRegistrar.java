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

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;

/**
 * The registrar of Bean.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class BeanRegistrar {

    private BeanRegistrar() {
    }

    /**
     * Register a Bean instance without the primary flag.
     *
     * @param registry the {@link BeanDefinitionRegistry}
     * @param beanName the name of Bean
     * @param bean     the Bean instance
     */
    public static void registerBean(BeanDefinitionRegistry registry, String beanName, Object bean) {
        registerBean(registry, beanName, bean, false);
    }

    /**
     * Register a Bean instance.
     *
     * @param registry the {@link BeanDefinitionRegistry}
     * @param beanName the name of Bean
     * @param bean     the Bean instance
     * @param primary  the Bean is primary or not
     */
    public static void registerBean(BeanDefinitionRegistry registry, String beanName, Object bean, boolean primary) {
        GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
        beanDefinition.setInstanceSupplier(() -> bean);
        // The instance supplier takes precedence over the bean class at creation time, the bean
        // class is declared so that type-based lookups(e.g. getBean(Class)) work before the bean is
        // instantiated
        beanDefinition.setBeanClass(AopUtils.getTargetClass(bean));
        beanDefinition.setPrimary(primary);
        registry.registerBeanDefinition(beanName, beanDefinition);
    }

    /**
     * Register a Bean instance as a {@link DelegatingFactoryBean}.
     *
     * @param registry the {@link BeanDefinitionRegistry}
     * @param beanName the name of Bean
     * @param bean     the Bean instance
     */
    public static void registerFactoryBean(BeanDefinitionRegistry registry, String beanName, Object bean) {
        registerFactoryBean(registry, beanName, bean, false);
    }

    /**
     * Register a Bean instance as a {@link DelegatingFactoryBean}.
     *
     * @param registry the {@link BeanDefinitionRegistry}
     * @param beanName the name of Bean
     * @param bean     the Bean instance
     * @param primary  the Bean is primary or not
     */
    public static void registerFactoryBean(BeanDefinitionRegistry registry, String beanName, Object bean, boolean primary) {
        GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
        beanDefinition.setBeanClass(DelegatingFactoryBean.class);
        beanDefinition.getConstructorArgumentValues().addGenericArgumentValue(bean);
        // The object type of the DelegatingFactoryBean is null, the delegate type is carried by the
        // bean definition attribute so that type-based lookups(e.g. getBean(Class)) still work
        beanDefinition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, AopUtils.getTargetClass(bean));
        beanDefinition.setPrimary(primary);
        registry.registerBeanDefinition(beanName, beanDefinition);
    }
}
