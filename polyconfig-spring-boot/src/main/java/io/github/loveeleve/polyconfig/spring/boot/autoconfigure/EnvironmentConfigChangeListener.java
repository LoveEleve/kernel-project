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
import io.github.loveeleve.polyconfig.spring.event.ConfigPropertyChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * The adapter that translates the Spring Cloud {@link EnvironmentChangeEvent} (published by the
 * standard config refresh of Nacos / Apollo / Spring Cloud Config) into the unified
 * {@link io.github.loveeleve.polyconfig.spring.event.ConfigPropertiesChangedEvent}.
 *
 * <p>
 * Thanks to this adapter, applications that use a Spring Cloud based configuration center get the
 * unified configuration change event without any declarative property source:
 * <pre>
 * public class MyListener implements ApplicationListener&lt;ConfigPropertiesChangedEvent&gt; {
 *     ...
 * }
 * </pre>
 *
 * <p>
 * Note: {@link EnvironmentChangeEvent} only carries the changed property names, so the old values
 * in the published changes are {@code null}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ConfigChangePublisher
 * @since 1.0.0
 */
public class EnvironmentConfigChangeListener implements ApplicationListener<EnvironmentChangeEvent> {

    /**
     * The source name of the events published by this adapter
     */
    public static final String SOURCE_NAME = "environment";

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentConfigChangeListener.class);

    private final ConfigChangePublisher changePublisher;

    private final Environment environment;

    public EnvironmentConfigChangeListener(ConfigChangePublisher changePublisher, Environment environment) {
        this.changePublisher = changePublisher;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        List<ConfigPropertyChangedEvent> changes = new ArrayList<>(event.getKeys().size());
        for (String propertyName : event.getKeys()) {
            changes.add(new ConfigPropertyChangedEvent(propertyName, null, environment.getProperty(propertyName)));
        }
        logger.info("The EnvironmentChangeEvent is translated into a ConfigPropertiesChangedEvent, changed properties : {}",
                event.getKeys());
        changePublisher.publish(SOURCE_NAME, changes);
    }
}
