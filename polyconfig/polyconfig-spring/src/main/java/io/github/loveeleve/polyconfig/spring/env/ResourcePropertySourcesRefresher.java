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
package io.github.loveeleve.polyconfig.spring.env;

import io.github.loveeleve.polyconfig.spring.event.ConfigChangePublisher;
import io.github.loveeleve.polyconfig.spring.event.ConfigPropertyChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The refresher of resource based property sources: it reloads a resource, computes the property
 * diff, replaces the property source in the {@code Environment} and publishes a
 * {@link io.github.loveeleve.polyconfig.spring.event.ConfigPropertiesChangedEvent}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ConfigChangePublisher
 * @since 1.0.0
 */
public class ResourcePropertySourcesRefresher {

    private static final Logger logger = LoggerFactory.getLogger(ResourcePropertySourcesRefresher.class);

    private final String sourceName;

    private final String encoding;

    private final Map<String, List<PropertySource<?>>> resourceSources;

    private final MutablePropertySources propertySources;

    private final ConfigChangePublisher changePublisher;

    public ResourcePropertySourcesRefresher(String sourceName, Map<String, List<PropertySource<?>>> resourceSources,
                                            MutablePropertySources propertySources,
                                            ConfigChangePublisher changePublisher, String encoding) {
        this.sourceName = sourceName;
        this.resourceSources = resourceSources;
        this.propertySources = propertySources;
        this.changePublisher = changePublisher;
        this.encoding = encoding;
    }

    /**
     * @return the character encoding of the managed resources
     */
    public String getEncoding() {
        return encoding;
    }

    /**
     * @return the resource values managed by this refresher
     */
    public synchronized Set<String> getResourceValues() {
        return new LinkedHashSet<>(resourceSources.keySet());
    }

    /**
     * Refresh the property sources of the given resource value with the new resource content.
     *
     * <p>
     * The method is thread-safe: the state update is performed under the monitor of this refresher,
     * while the event is published outside the lock so that listeners never run while holding it.
     *
     * @param resourceValue the resource value (e.g. the Nacos dataId)
     * @param resource      the new resource content
     */
    public void refresh(String resourceValue, Resource resource) {
        List<ConfigPropertyChangedEvent> changes = doRefresh(resourceValue, resource);
        if (changes != null && !changes.isEmpty()) {
            logger.info("The property source [{}] is refreshed, {} propert{} changed", sourceName, changes.size(),
                    changes.size() > 1 ? "ies" : "y");
            changePublisher.publish(sourceName, changes);
        }
    }

    private synchronized List<ConfigPropertyChangedEvent> doRefresh(String resourceValue, Resource resource) {
        List<PropertySource<?>> newPropertySources;
        try {
            newPropertySources = ResourcePropertySources.load(resourceValue, resource, encoding);
        } catch (Throwable e) {
            logger.error("The resource [{}] can't be reloaded for the property source [{}]", resourceValue, sourceName, e);
            return null;
        }

        Map<String, Object> oldProperties = ResourcePropertySources.flatten(resourceSources.get(resourceValue));
        Map<String, Object> newProperties = ResourcePropertySources.flatten(newPropertySources);

        List<ConfigPropertyChangedEvent> changes = diff(oldProperties, newProperties);
        if (changes.isEmpty()) {
            logger.info("The properties of the resource [{}] are not changed in the property source [{}]", resourceValue,
                    sourceName);
            return null;
        }

        resourceSources.put(resourceValue, newPropertySources);
        rebuildAndReplace();
        return changes;
    }

    private List<ConfigPropertyChangedEvent> diff(Map<String, Object> oldProperties, Map<String, Object> newProperties) {
        Set<String> propertyNames = new LinkedHashSet<>(oldProperties.keySet());
        propertyNames.addAll(newProperties.keySet());

        List<ConfigPropertyChangedEvent> changes = new ArrayList<>();
        for (String propertyName : propertyNames) {
            Object oldValue = oldProperties.get(propertyName);
            Object newValue = newProperties.get(propertyName);
            if (!Objects.equals(oldValue, newValue)) {
                changes.add(new ConfigPropertyChangedEvent(propertyName, oldValue, newValue));
            }
        }
        return changes;
    }

    private void rebuildAndReplace() {
        CompositePropertySource compositePropertySource = new CompositePropertySource(sourceName);
        for (List<PropertySource<?>> propertySources : resourceSources.values()) {
            for (PropertySource<?> propertySource : propertySources) {
                compositePropertySource.addPropertySource(propertySource);
            }
        }
        if (propertySources.contains(sourceName)) {
            propertySources.replace(sourceName, compositePropertySource);
        } else {
            propertySources.addLast(compositePropertySource);
        }
    }

    /**
     * A snapshot of the managed resources, for testing and diagnostics.
     *
     * @return the resource value to property source names mapping
     */
    public synchronized Map<String, List<String>> getResourcePropertySourceNames() {
        Map<String, List<String>> result = new LinkedHashMap<>(resourceSources.size());
        resourceSources.forEach((resourceValue, sources) -> {
            List<String> names = new ArrayList<>(sources.size());
            sources.forEach(source -> names.add(source.getName()));
            result.put(resourceValue, names);
        });
        return result;
    }
}
