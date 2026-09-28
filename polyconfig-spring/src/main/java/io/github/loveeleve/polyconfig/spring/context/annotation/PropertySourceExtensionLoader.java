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
package io.github.loveeleve.polyconfig.spring.context.annotation;

import io.github.loveeleve.polyconfig.spring.env.ResourcePropertySources;
import io.github.loveeleve.polyconfig.spring.env.ResourcePropertySourcesRefresher;
import io.github.loveeleve.polyconfig.spring.event.ConfigChangePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The abstract loader of a declarative external {@link PropertySource}.
 *
 * <p>
 * A backend annotation meta-annotated with {@link PropertySourceExtension} declares
 * {@code @Import(TheBackendLoader.class)}; Spring instantiates the loader while parsing the
 * configuration class and invokes {@link #selectImports(AnnotationMetadata)}. The loader then:
 * <ol>
 *     <li>reads the backend annotation attributes from the annotated class</li>
 *     <li>resolves the resources through {@link #resolveResources(AnnotationAttributes, String, String)}
 *     (implemented by the backend, e.g. fetch the configuration from Nacos)</li>
 *     <li>loads the resources into a {@link CompositePropertySource} and registers it into the
 *     {@code Environment} ({@code first} / {@code before} / {@code after} supported)</li>
 *     <li>when {@code autoRefreshed=true}, creates a {@link ResourcePropertySourcesRefresher} and
 *     delegates the backend to register its change listener through
 *     {@link #configureRefresher(AnnotationAttributes, ResourcePropertySourcesRefresher)}</li>
 * </ol>
 *
 * @param <A> the backend annotation type meta-annotated with {@link PropertySourceExtension}
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see PropertySourceExtension
 * @since 1.0.0
 */
public abstract class PropertySourceExtensionLoader<A extends Annotation>
        implements ImportSelector, EnvironmentAware, ResourceLoaderAware, BeanFactoryAware {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected Environment environment;

    protected ResourceLoader resourceLoader;

    protected ConfigurableListableBeanFactory beanFactory;

    @Override
    public final String[] selectImports(AnnotationMetadata importingClassMetadata) {
        Class<A> annotationType = getAnnotationType();
        Map<String, Object> attributes = importingClassMetadata.getAnnotationAttributes(annotationType.getName());
        if (attributes == null) {
            logger.warn("The annotation [{}] can't be found on the class [{}]", annotationType.getName(),
                    importingClassMetadata.getClassName());
            return new String[0];
        }
        loadPropertySource(new AnnotationAttributes(attributes), importingClassMetadata);
        return new String[0];
    }

    /**
     * @return the backend annotation type, e.g. {@code NacosPropertySource.class}
     */
    protected abstract Class<A> getAnnotationType();

    /**
     * Resolve the resources from the backend configuration center.
     *
     * @param attributes         the backend annotation attributes
     * @param propertySourceName the name of the target property source
     * @param resourceValue      the resource value (e.g. the Nacos dataId)
     * @return the resolved resources; an empty array means the resource was not found
     * @throws Throwable if the resources can't be resolved
     */
    protected abstract Resource[] resolveResources(AnnotationAttributes attributes, String propertySourceName,
                                                   String resourceValue) throws Throwable;

    /**
     * Configure the backend change listener so that {@code refresher.refresh(resourceValue, resource)}
     * is invoked when the configuration changes on the server side.
     *
     * @param attributes the backend annotation attributes
     * @param refresher  the refresher
     * @throws Throwable if the listener can't be registered
     */
    protected void configureRefresher(AnnotationAttributes attributes, ResourcePropertySourcesRefresher refresher)
            throws Throwable {
        // no-op by default
    }

    protected void loadPropertySource(AnnotationAttributes attributes, AnnotationMetadata metadata) {
        String propertySourceName = resolvePropertySourceName(attributes, metadata);
        String[] resourceValues = getResourceValues(attributes);
        if (ObjectUtils.isEmpty(resourceValues)) {
            logger.warn("No resource value was specified by the annotation [{}] on the class [{}]",
                    getAnnotationType().getName(), metadata.getClassName());
            return;
        }

        ConfigurableEnvironment environment = getConfigurableEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();
        CompositePropertySource compositePropertySource = new CompositePropertySource(propertySourceName);
        Map<String, List<PropertySource<?>>> resourceSources = new LinkedHashMap<>();
        String encoding = resolveEncoding(attributes);
        boolean ignoreResourceNotFound = getBoolean(attributes, "ignoreResourceNotFound", false);

        for (String resourceValue : resourceValues) {
            try {
                Resource[] resources = resolveResources(attributes, propertySourceName, resourceValue);
                List<PropertySource<?>> loadedPropertySources = new ArrayList<>();
                if (!ObjectUtils.isEmpty(resources)) {
                    for (Resource resource : resources) {
                        loadedPropertySources.addAll(ResourcePropertySources.load(resourceValue, resource, encoding));
                    }
                }
                if (loadedPropertySources.isEmpty()) {
                    handleResourceNotFound(ignoreResourceNotFound, metadata, resourceValue);
                    continue;
                }
                loadedPropertySources.forEach(compositePropertySource::addPropertySource);
                resourceSources.put(resourceValue, loadedPropertySources);
            } catch (Throwable e) {
                if (ignoreResourceNotFound) {
                    logger.warn("The property source resource [{}] of the class [{}] can't be loaded", resourceValue,
                            metadata.getClassName(), e);
                } else {
                    throw new IllegalStateException("The property source resource [" + resourceValue
                            + "] of the class [" + metadata.getClassName() + "] can't be loaded", e);
                }
            }
        }

        if (compositePropertySource.getPropertySources().isEmpty()) {
            logger.warn("No property source was loaded by the annotation [{}] on the class [{}]",
                    getAnnotationType().getName(), metadata.getClassName());
            return;
        }

        addPropertySource(attributes, propertySources, compositePropertySource);
        logger.info("The property source [{}] has been added into the Environment by the annotation [{}] on the class [{}]",
                propertySourceName, getAnnotationType().getName(), metadata.getClassName());

        if (getBoolean(attributes, "autoRefreshed", false)) {
            ResourcePropertySourcesRefresher refresher = new ResourcePropertySourcesRefresher(propertySourceName,
                    resourceSources, propertySources, createConfigChangePublisher(), encoding);
            try {
                configureRefresher(attributes, refresher);
            } catch (Throwable e) {
                throw new IllegalStateException("The refresher of the property source [" + propertySourceName
                        + "] can't be configured", e);
            }
            logger.info("The property source [{}] will be auto-refreshed, the managed resources are {}",
                    propertySourceName, refresher.getResourceValues());
        }
    }

    protected String[] getResourceValues(AnnotationAttributes attributes) {
        return getStringArray(attributes, "value");
    }

    protected String resolvePropertySourceName(AnnotationAttributes attributes, AnnotationMetadata metadata) {
        String name = getString(attributes, "name", null);
        if (StringUtils.hasText(name)) {
            return name;
        }
        return getAnnotationType().getSimpleName() + "@" + metadata.getClassName();
    }

    protected String resolveEncoding(AnnotationAttributes attributes) {
        String encoding = getString(attributes, "encoding", null);
        return StringUtils.hasText(encoding) ? encoding : "UTF-8";
    }

    protected void addPropertySource(AnnotationAttributes attributes, MutablePropertySources propertySources,
                                     PropertySource<?> propertySource) {
        if (getBoolean(attributes, "first", false)) {
            propertySources.addFirst(propertySource);
            return;
        }
        String after = getString(attributes, "after", null);
        if (StringUtils.hasText(after)) {
            if (propertySources.contains(after)) {
                propertySources.addAfter(after, propertySource);
                return;
            }
            logger.debug("The property source [{}] is not found, the 'after' attribute is ignored", after);
        }
        String before = getString(attributes, "before", null);
        if (StringUtils.hasText(before)) {
            if (propertySources.contains(before)) {
                propertySources.addBefore(before, propertySource);
                return;
            }
            logger.debug("The property source [{}] is not found, the 'before' attribute is ignored", before);
        }
        propertySources.addLast(propertySource);
    }

    protected ConfigChangePublisher createConfigChangePublisher() {
        ApplicationEventPublisher eventPublisher = null;
        // In a Spring application, the ResourceLoader injected by Spring is the ApplicationContext
        // itself, which is an ApplicationEventPublisher
        if (resourceLoader instanceof ApplicationEventPublisher) {
            eventPublisher = (ApplicationEventPublisher) resourceLoader;
        }
        if (eventPublisher == null && beanFactory != null) {
            try {
                eventPublisher = beanFactory.getBeanProvider(ApplicationEventPublisher.class).getIfAvailable();
            } catch (Throwable e) {
                logger.warn("The ApplicationEventPublisher can't be resolved from the BeanFactory", e);
            }
        }
        if (eventPublisher == null) {
            logger.warn("No ApplicationEventPublisher is available, the configuration changes will not be published");
            eventPublisher = event -> {
            };
        }
        return new ConfigChangePublisher(eventPublisher);
    }

    private void handleResourceNotFound(boolean ignoreResourceNotFound, AnnotationMetadata metadata,
                                        String resourceValue) {
        String message = "The property source resource [" + resourceValue + "] of the class ["
                + metadata.getClassName() + "] can't be found";
        if (ignoreResourceNotFound) {
            logger.warn(message);
        } else {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Get a {@link String} attribute with the default value applied when the backend annotation does
     * not declare it.
     */
    protected String getString(AnnotationAttributes attributes, String attributeName, String defaultValue) {
        Object value = attributes.get(attributeName);
        return value instanceof String ? (String) value : defaultValue;
    }

    /**
     * Get a {@code boolean} attribute with the default value applied when the backend annotation does
     * not declare it.
     */
    protected boolean getBoolean(AnnotationAttributes attributes, String attributeName, boolean defaultValue) {
        Object value = attributes.get(attributeName);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    /**
     * Get a {@link String} array attribute, tolerating both {@code String[]} and {@code Object[]}.
     */
    protected String[] getStringArray(AnnotationAttributes attributes, String attributeName) {
        Object value = attributes.get(attributeName);
        if (value instanceof String[]) {
            return (String[]) value;
        }
        if (value instanceof Object[]) {
            Object[] array = (Object[]) value;
            String[] result = new String[array.length];
            for (int i = 0; i < array.length; i++) {
                result[i] = array[i] == null ? null : array[i].toString();
            }
            return result;
        }
        return new String[0];
    }

    protected ConfigurableEnvironment getConfigurableEnvironment() {
        if (environment instanceof ConfigurableEnvironment) {
            return (ConfigurableEnvironment) environment;
        }
        throw new IllegalStateException("The Environment [" + environment + "] is not a ConfigurableEnvironment");
    }

    @Override
    public final void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public final void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public final void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof ConfigurableListableBeanFactory) {
            this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
        }
    }
}
