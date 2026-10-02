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

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationStartupAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.env.Environment;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.util.StringValueResolver;

/**
 * The utilities class for Bean.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class BeanUtils {

    private BeanUtils() {
    }

    /**
     * Invoke the {@link org.springframework.beans.factory.Aware Aware} interfaces on the specified
     * bean with the given {@link ApplicationContext}.
     *
     * @param bean    the bean
     * @param context the {@link ApplicationContext}
     */
    public static void invokeAwareInterfaces(Object bean, ApplicationContext context) {
        if (bean instanceof EnvironmentAware) {
            ((EnvironmentAware) bean).setEnvironment(context.getEnvironment());
        }
        if (bean instanceof EmbeddedValueResolverAware) {
            ((EmbeddedValueResolverAware) bean).setEmbeddedValueResolver(embeddedValueResolver(context));
        }
        if (bean instanceof ResourceLoaderAware) {
            ((ResourceLoaderAware) bean).setResourceLoader(context);
        }
        if (bean instanceof ApplicationEventPublisherAware) {
            ((ApplicationEventPublisherAware) bean).setApplicationEventPublisher(context);
        }
        if (bean instanceof MessageSourceAware) {
            ((MessageSourceAware) bean).setMessageSource(context);
        }
        if (bean instanceof ApplicationStartupAware) {
            ((ApplicationStartupAware) bean).setApplicationStartup(applicationStartup(context));
        }
        if (bean instanceof ApplicationContextAware) {
            ((ApplicationContextAware) bean).setApplicationContext(context);
        }
        if (bean instanceof BeanFactoryAware) {
            ((BeanFactoryAware) bean).setBeanFactory(beanFactory(context));
        }
        if (bean instanceof BeanClassLoaderAware) {
            ((BeanClassLoaderAware) bean).setBeanClassLoader(context.getClassLoader());
        }
    }

    /**
     * Invoke the {@link org.springframework.beans.factory.Aware Aware} interfaces and the
     * {@link InitializingBean#afterPropertiesSet()} callback on the specified bean with the given
     * {@link ApplicationContext}.
     *
     * @param bean    the bean
     * @param context the {@link ApplicationContext}
     */
    public static void invokeBeanInterfaces(Object bean, ApplicationContext context) {
        invokeAwareInterfaces(bean, context);
        invokeInitializingBean(bean);
    }

    /**
     * Invoke the {@link org.springframework.beans.factory.Aware Aware} interfaces on the specified
     * bean with the given {@link BeanFactory}.
     *
     * <p>
     * When the {@link BeanFactory} is an {@link ApplicationContext}, all the supported Aware
     * interfaces are invoked, otherwise only the {@link BeanFactoryAware} and
     * {@link BeanClassLoaderAware} callbacks are invoked.
     *
     * @param bean       the bean
     * @param beanFactory the {@link BeanFactory}
     */
    public static void invokeAwareInterfaces(Object bean, BeanFactory beanFactory) {
        if (beanFactory instanceof ApplicationContext) {
            invokeAwareInterfaces(bean, (ApplicationContext) beanFactory);
            return;
        }
        if (bean instanceof BeanFactoryAware) {
            ((BeanFactoryAware) bean).setBeanFactory(beanFactory);
        }
        if (bean instanceof BeanClassLoaderAware && beanFactory instanceof ConfigurableBeanFactory) {
            ((BeanClassLoaderAware) bean).setBeanClassLoader(((ConfigurableBeanFactory) beanFactory).getBeanClassLoader());
        }
        if (bean instanceof EnvironmentAware && beanFactory instanceof ConfigurableBeanFactory) {
            Environment environment = ((ConfigurableBeanFactory) beanFactory).getBeanProvider(Environment.class)
                    .getIfAvailable();
            if (environment != null) {
                ((EnvironmentAware) bean).setEnvironment(environment);
            }
        }
    }

    private static BeanFactory beanFactory(ApplicationContext context) {
        if (context instanceof ConfigurableApplicationContext) {
            return ((ConfigurableApplicationContext) context).getBeanFactory();
        }
        return context.getAutowireCapableBeanFactory();
    }

    private static StringValueResolver embeddedValueResolver(ApplicationContext context) {
        if (context instanceof ConfigurableApplicationContext) {
            return ((ConfigurableApplicationContext) context).getBeanFactory()::resolveEmbeddedValue;
        }
        return context.getEnvironment()::resolvePlaceholders;
    }

    private static ApplicationStartup applicationStartup(ApplicationContext context) {
        if (context instanceof ConfigurableApplicationContext) {
            return ((ConfigurableApplicationContext) context).getApplicationStartup();
        }
        return ApplicationStartup.DEFAULT;
    }

    private static void invokeInitializingBean(Object bean) {
        if (bean instanceof InitializingBean) {
            try {
                ((InitializingBean) bean).afterPropertiesSet();
            } catch (Exception e) {
                throw new RuntimeException("The 'afterPropertiesSet' method of " + bean.getClass().getName()
                        + " can't be invoked", e);
            }
        }
    }
}
