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
package io.github.loveeleve.polyconfig.spring.boot.autoconfigure;

import io.github.loveeleve.polyconfig.spring.event.ConfigChangePublisher;
import io.github.loveeleve.polyconfig.spring.event.ConfigPropertiesChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PolyConfigAutoConfiguration}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class PolyConfigAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PolyConfigAutoConfiguration.class));

    @Test
    void testBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ConfigChangePublisher.class);
            assertThat(context).hasSingleBean(EnvironmentConfigChangeListener.class);
        });
    }

    @Test
    void testEnvironmentChangeEventIsTranslated() {
        contextRunner.run(context -> {
            ConfigurableApplicationContext sourceContext = context.getSourceApplicationContext();
            List<ConfigPropertiesChangedEvent> events = new ArrayList<>();
            sourceContext.addApplicationListener(event -> {
                if (event instanceof ConfigPropertiesChangedEvent) {
                    events.add((ConfigPropertiesChangedEvent) event);
                }
            });

            Map<String, Object> properties = new HashMap<>();
            properties.put("polyconfig.test.key", "value-1");
            sourceContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));

            sourceContext.publishEvent(new EnvironmentChangeEvent(Set.of("polyconfig.test.key")));

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getSourceName()).isEqualTo(EnvironmentConfigChangeListener.SOURCE_NAME);
            assertThat(events.get(0).getNewProperties()).containsEntry("polyconfig.test.key", "value-1");
            assertThat(events.get(0).isChanged("polyconfig.test.key")).isTrue();
        });
    }
}
