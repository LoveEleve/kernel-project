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
import io.github.loveeleve.polyconfig.spring.event.ConfigPropertiesChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ResourcePropertySourcesRefresher}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ResourcePropertySourcesRefresherTest {

    @Test
    void testRefreshUpdatesEnvironmentAndPublishesEvent() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MutablePropertySources propertySources = context.getEnvironment().getPropertySources();

            List<PropertySource<?>> initial = ResourcePropertySources.load("test", resource("a=1\nb=2"), "UTF-8");
            Map<String, List<PropertySource<?>>> resourceSources = new LinkedHashMap<>();
            resourceSources.put("test", initial);

            CompositePropertySource compositePropertySource = new CompositePropertySource("test");
            initial.forEach(compositePropertySource::addPropertySource);
            propertySources.addFirst(compositePropertySource);

            List<ConfigPropertiesChangedEvent> events = new ArrayList<>();
            context.addApplicationListener(event -> {
                if (event instanceof ConfigPropertiesChangedEvent) {
                    events.add((ConfigPropertiesChangedEvent) event);
                }
            });

            ResourcePropertySourcesRefresher refresher = new ResourcePropertySourcesRefresher("test", resourceSources,
                    propertySources, new ConfigChangePublisher(context), "UTF-8");

            refresher.refresh("test", resource("a=1\nb=3"));

            assertEquals("3", context.getEnvironment().getProperty("b"));
            assertEquals("1", context.getEnvironment().getProperty("a"));
            assertEquals(1, events.size());
            assertEquals("b", events.get(0).getChanges().get(0).getPropertyName());
            assertEquals("2", events.get(0).getChanges().get(0).getOldValue());
            assertEquals("3", events.get(0).getChanges().get(0).getNewValue());
        }
    }

    @Test
    void testRefreshWithUnchangedContentPublishesNothing() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MutablePropertySources propertySources = context.getEnvironment().getPropertySources();

            List<PropertySource<?>> initial = ResourcePropertySources.load("test", resource("a=1"), "UTF-8");
            Map<String, List<PropertySource<?>>> resourceSources = new LinkedHashMap<>();
            resourceSources.put("test", initial);

            CompositePropertySource compositePropertySource = new CompositePropertySource("test");
            initial.forEach(compositePropertySource::addPropertySource);
            propertySources.addFirst(compositePropertySource);

            List<ConfigPropertiesChangedEvent> events = new ArrayList<>();
            context.addApplicationListener(event -> {
                if (event instanceof ConfigPropertiesChangedEvent) {
                    events.add((ConfigPropertiesChangedEvent) event);
                }
            });

            ResourcePropertySourcesRefresher refresher = new ResourcePropertySourcesRefresher("test", resourceSources,
                    propertySources, new ConfigChangePublisher(context), "UTF-8");

            refresher.refresh("test", resource("a=1"));

            assertEquals(0, events.size());
            assertEquals("1", context.getEnvironment().getProperty("a"));
        }
    }

    @Test
    void testGetResourceValues() throws Exception {
        Map<String, List<PropertySource<?>>> resourceSources = new LinkedHashMap<>();
        resourceSources.put("app.properties", ResourcePropertySources.load("app.properties", resource("a=1"), "UTF-8"));

        ResourcePropertySourcesRefresher refresher = new ResourcePropertySourcesRefresher("test", resourceSources,
                new MutablePropertySources(), new ConfigChangePublisher(event -> {
        }), "UTF-8");

        assertEquals(1, refresher.getResourceValues().size());
        assertEquals("app.properties", refresher.getResourceValues().iterator().next());
    }

    private static ByteArrayResource resource(String content) {
        return new ByteArrayResource(content.getBytes(UTF_8));
    }
}
