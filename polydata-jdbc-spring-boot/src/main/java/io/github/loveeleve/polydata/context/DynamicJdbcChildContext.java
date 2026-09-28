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
package io.github.loveeleve.polydata.context;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.util.LinkedList;
import java.util.List;

import static io.github.loveeleve.polydata.support.SpringBootPropertyConstants.ATTACHED_PROPERTY_SOURCE_NAME;
import static io.github.loveeleve.polydata.support.SpringBootPropertyConstants.SPRING_AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.generateSynthesizedPropertySourceName;

/**
 * Dynamic JDBC Child Context that extends {@link AnnotationConfigApplicationContext}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see DynamicJdbcContextApplicationListener
 * @see AnnotationConfigApplicationContext
 * @since 1.0.0
 */
public class DynamicJdbcChildContext extends AnnotationConfigApplicationContext {

    private static final Logger logger = LoggerFactory.getLogger(DynamicJdbcChildContext.class);

    protected final DynamicJdbcConfig dynamicJdbcConfig;

    protected final String dynamicJdbcConfigPropertyName;

    protected final ConfigurableApplicationContext parentContext;

    private boolean registerParentBeans = false;

    public DynamicJdbcChildContext(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName,
                                    ConfigurableApplicationContext parentContext, DynamicJdbcChildContextIdGenerator generator) {
        this.dynamicJdbcConfig = dynamicJdbcConfig;
        this.dynamicJdbcConfigPropertyName = dynamicJdbcConfigPropertyName;
        this.parentContext = parentContext;
        String id = generator.generate(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, parentContext);
        this.setId(id);
    }

    protected DynamicJdbcChildContext(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName,
                                       ConfigurableApplicationContext parentContext) {
        this(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, parentContext, DynamicJdbcChildContextIdGenerator.DEFAULT);
    }

    public void registerParentBeans() {
        this.registerParentBeans = true;
    }

    public void mergeParentEnvironment() {
        ConfigurableEnvironment environment = getEnvironment();
        ConfigurableEnvironment parentEnvironment = parentContext.getEnvironment();
        if (parentEnvironment != null) {
            environment.merge(parentEnvironment);
        }
        removeSynthesizedPropertySources(environment);
        detachConfigurationPropertySources(environment);
    }

    private void removeSynthesizedPropertySources(ConfigurableEnvironment environment) {
        removeSynthesizedPropertySources(environment, dynamicJdbcConfigPropertyName, SPRING_AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME);
    }

    private void removeSynthesizedPropertySources(ConfigurableEnvironment environment, String... propertyNames) {
        MutablePropertySources propertySources = environment.getPropertySources();
        for (String propertyName : propertyNames) {
            String propertySourceName = generateSynthesizedPropertySourceName(propertyName);
            if (propertySources.contains(propertySourceName)) {
                propertySources.remove(propertySourceName);
            }
        }
    }

    private void detachConfigurationPropertySources(ConfigurableEnvironment environment) {
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> attached = sources.get(ATTACHED_PROPERTY_SOURCE_NAME);
        if (attached != null && attached.getSource() != sources) {
            sources.remove(ATTACHED_PROPERTY_SOURCE_NAME);
        }
    }

    @Override
    public final void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        super.postProcessBeanFactory(beanFactory);
        // Set Class Loader
        setClassLoader(parentContext.getClassLoader());
        // Prepare Environment
        prepareEnvironment(getEnvironment());
        // Register Listener
        addApplicationListener(new DynamicJdbcChildContextRefreshedListener(dynamicJdbcConfig, parentContext, beanFactory, registerParentBeans));
        // Register Configuration Classes
        registerConfigurationClasses();
        // Customize BeanFactory
        customizeBeanFactory(beanFactory);
        // Process Dynamic JDBC Child Context
        processDynamicJdbcChildContext();
    }

    @Override
    public void finishRefresh() {
        super.finishRefresh();
        logger.info("{} finishes refreshing", getId());
    }

    protected void prepareEnvironment(ConfigurableEnvironment environment) {
        // Attach the ConfigurationPropertySources for the Spring Boot Binder
        ConfigurationPropertySources.attach(getEnvironment());
    }


    private void registerConfigurationClasses() {
        List<Class<?>> configurationClasses = new LinkedList<>();
        setupConfigurationClasses(configurationClasses);
        configurationClasses.forEach(this::register);
    }

    /**
     * Subclass could customize BeanFactory
     *
     * @param beanFactory {@link ConfigurableListableBeanFactory}
     */
    protected void customizeBeanFactory(ConfigurableListableBeanFactory beanFactory) {}

    /**
     * Setup {@link Configuration} classes
     *
     * @param configurationClasses {@link Configuration} classes
     */
    protected void setupConfigurationClasses(List<Class<?>> configurationClasses) {
        configurationClasses.add(DynamicJdbcChildContextConfiguration.class);
    }

    private void processDynamicJdbcChildContext() {
        DynamicJdbcContextProcessor processor = new DynamicJdbcContextProcessor();
        processor.process(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, this);
    }

    public ConfigurableApplicationContext getParentContext() {
        return parentContext;
    }
}
