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

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ConfigChangePublisher}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ConfigChangePublisherTest {

    @Test
    void testPublish() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            List<ConfigPropertiesChangedEvent> events = new ArrayList<>();
            context.addApplicationListener(event -> {
                if (event instanceof ConfigPropertiesChangedEvent) {
                    events.add((ConfigPropertiesChangedEvent) event);
                }
            });

            ConfigChangePublisher publisher = new ConfigChangePublisher(context);
            publisher.publish("nacos", List.of(new ConfigPropertyChangedEvent("app.name", "old", "new")));

            assertEquals(1, events.size());
            assertEquals("nacos", events.get(0).getSourceName());
            assertEquals("new", events.get(0).getNewProperties().get("app.name"));
        }
    }

    @Test
    void testPublishWithEmptyChangesIsSkipped() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            List<ConfigPropertiesChangedEvent> events = new ArrayList<>();
            context.addApplicationListener(event -> {
                if (event instanceof ConfigPropertiesChangedEvent) {
                    events.add((ConfigPropertiesChangedEvent) event);
                }
            });

            ConfigChangePublisher publisher = new ConfigChangePublisher(context);
            publisher.publish("nacos", List.of());
            publisher.publish("nacos", null);

            assertEquals(0, events.size());
        }
    }
}
