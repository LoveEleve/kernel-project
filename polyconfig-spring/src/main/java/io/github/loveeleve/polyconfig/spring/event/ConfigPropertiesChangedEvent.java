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
package io.github.loveeleve.polyconfig.spring.event;

import org.springframework.context.ApplicationEvent;
import org.springframework.util.Assert;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;

/**
 * The unified configuration properties changed event, published by every PolyConfig backend
 * adapter (declarative property source loaders, the Spring Cloud environment change adapter, ...).
 *
 * <p>
 * Consumers (e.g. a dynamic data source that must rebuild on configuration changes) only need to
 * listen to this single event type, no matter which configuration center produced the change:
 * <pre>
 * public class MyListener implements ApplicationListener&lt;ConfigPropertiesChangedEvent&gt; {
 *     public void onApplicationEvent(ConfigPropertiesChangedEvent event) {
 *         if (event.isChanged("polyzone.dynamic.jdbc.configs.orders")) {
 *             // rebuild ...
 *         }
 *     }
 * }
 * </pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class ConfigPropertiesChangedEvent extends ApplicationEvent {

    private final String sourceName;

    private final List<ConfigPropertyChangedEvent> changes;

    /**
     * Create a new event.
     *
     * @param eventSource the object on which the event initially occurred (must not be {@code null})
     * @param sourceName  the name of the configuration source (e.g. {@code "environment"}, {@code "nacos"})
     * @param changes     the property changes (must not be empty)
     */
    public ConfigPropertiesChangedEvent(Object eventSource, String sourceName, List<ConfigPropertyChangedEvent> changes) {
        super(eventSource);
        Assert.hasText(sourceName, "The 'sourceName' must not be empty!");
        Assert.notEmpty(changes, "The 'changes' must not be empty!");
        this.sourceName = sourceName;
        this.changes = unmodifiableList(changes);
    }

    /**
     * @return the name of the configuration source
     */
    public String getSourceName() {
        return sourceName;
    }

    /**
     * @return the property changes, in the order they were detected
     */
    public List<ConfigPropertyChangedEvent> getChanges() {
        return changes;
    }

    /**
     * @return the names of all changed properties
     */
    public Set<String> getChangedPropertyNames() {
        Set<String> propertyNames = new LinkedHashSet<>(changes.size());
        for (ConfigPropertyChangedEvent change : changes) {
            propertyNames.add(change.getPropertyName());
        }
        return unmodifiableSet(propertyNames);
    }

    /**
     * @return the new values of all changed properties
     */
    public Map<String, Object> getNewProperties() {
        return getValues(false);
    }

    /**
     * @return the old values of all changed properties (values may be {@code null})
     */
    public Map<String, Object> getOldProperties() {
        return getValues(true);
    }

    /**
     * Whether the given property was changed in this event.
     *
     * @param propertyName the property name
     * @return {@code true} if changed
     */
    public boolean isChanged(String propertyName) {
        return getChange(propertyName) != null;
    }

    /**
     * Get the change of the given property.
     *
     * @param propertyName the property name
     * @return the change, or {@code null} if the property was not changed
     */
    public ConfigPropertyChangedEvent getChange(String propertyName) {
        for (ConfigPropertyChangedEvent change : changes) {
            if (change.getPropertyName().equals(propertyName)) {
                return change;
            }
        }
        return null;
    }

    private Map<String, Object> getValues(boolean old) {
        Map<String, Object> values = new LinkedHashMap<>(changes.size());
        for (ConfigPropertyChangedEvent change : changes) {
            values.put(change.getPropertyName(), old ? change.getOldValue() : change.getNewValue());
        }
        return unmodifiableMap(values);
    }

    @Override
    public String toString() {
        return "ConfigPropertiesChangedEvent{"
                + "sourceName='" + sourceName + '\''
                + ", changes=" + changes
                + '}';
    }
}
