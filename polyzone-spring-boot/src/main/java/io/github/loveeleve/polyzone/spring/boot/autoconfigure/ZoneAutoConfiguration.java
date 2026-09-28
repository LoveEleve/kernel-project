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
package io.github.loveeleve.polyzone.spring.boot.autoconfigure;

import io.github.loveeleve.polyzone.ZoneContext;
import io.github.loveeleve.polyzone.spring.CompositeZoneLocator;
import io.github.loveeleve.polyzone.spring.ZoneLocator;
import io.github.loveeleve.polyzone.spring.boot.condition.ConditionalOnZoneAvailable;
import io.github.loveeleve.polyzone.spring.event.ZoneContextChangedListener;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationStartupAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static io.github.loveeleve.polyzone.ZoneContext.get;
import static io.github.loveeleve.polyzone.spring.ZoneUtils.ZONE_CONTEXT_BEAN_NAME;
import static io.github.loveeleve.polyzone.spring.ZoneUtils.ZONE_LOCATOR_BEAN_NAME;

/**
 * Zone Auto-Configuration.
 *
 * <p>
 * It exposes the singleton {@link ZoneContext} as a bean and builds the
 * {@link CompositeZoneLocator} from both {@link ZoneLocator} Spring beans and
 * {@code META-INF/spring.factories} declarations (e.g. the ones contributed by
 * {@code polyzone-aws}).
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnZoneAvailable
@Import(value = {ZoneContextChangedListener.class})
public class ZoneAutoConfiguration {

    @Bean(name = ZONE_CONTEXT_BEAN_NAME)
    @ConditionalOnMissingBean
    public ZoneContext zoneContext() {
        return get();
    }

    @Primary
    @Bean(name = ZONE_LOCATOR_BEAN_NAME)
    @ConditionalOnMissingBean
    public CompositeZoneLocator zoneLocator(Collection<ZoneLocator> zoneLocatorBeans,
                                            ConfigurableApplicationContext context) {
        // Load from Spring Factories
        List<ZoneLocator> zoneLocators =
                SpringFactoriesLoader.forDefaultResourceLocation(context.getClassLoader()).load(ZoneLocator.class);
        // Invoke the lifecycle callbacks (EnvironmentAware, ...) on the factory-loaded locators
        zoneLocators.forEach(zoneLocator -> invokeBeanInterfaces(zoneLocator, context));
        List<ZoneLocator> allZoneLocators = new ArrayList<>(zoneLocatorBeans.size() + zoneLocators.size());
        // Add ZoneLocator Spring Beans
        allZoneLocators.addAll(zoneLocatorBeans);
        // Add ZoneLocator Spring Factories
        allZoneLocators.addAll(zoneLocators);
        // Sort by the order
        AnnotationAwareOrderComparator.sort(allZoneLocators);
        return new CompositeZoneLocator(allZoneLocators);
    }

    /**
     * Invoke the Spring lifecycle callbacks on a factory-loaded object, mirroring the bean
     * lifecycle for the supported interfaces (excluding {@link org.springframework.beans.factory.BeanNameAware},
     * which requires a bean name that a factory-loaded object does not have).
     */
    private static void invokeBeanInterfaces(Object bean, ConfigurableApplicationContext context) {
        if (bean instanceof EnvironmentAware) {
            ((EnvironmentAware) bean).setEnvironment(context.getEnvironment());
        }
        if (bean instanceof EmbeddedValueResolverAware) {
            ((EmbeddedValueResolverAware) bean).setEmbeddedValueResolver(context.getBeanFactory()::resolveEmbeddedValue);
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
            ((ApplicationStartupAware) bean).setApplicationStartup(context.getApplicationStartup());
        }
        if (bean instanceof ApplicationContextAware) {
            ((ApplicationContextAware) bean).setApplicationContext(context);
        }
        if (bean instanceof BeanFactoryAware) {
            ((BeanFactoryAware) bean).setBeanFactory(context.getBeanFactory());
        }
        if (bean instanceof BeanClassLoaderAware) {
            ((BeanClassLoaderAware) bean).setBeanClassLoader(context.getClassLoader());
        }
        if (bean instanceof InitializingBean) {
            try {
                ((InitializingBean) bean).afterPropertiesSet();
            } catch (Exception e) {
                throw new IllegalStateException("The factory-loaded bean [" + bean + "] failed to initialize", e);
            }
        }
    }

}
