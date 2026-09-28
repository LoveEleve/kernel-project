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
import io.github.loveeleve.polyzone.spring.event.ZoneContextChangedEvent;
import io.github.loveeleve.polyconfig.spring.event.ConfigPropertiesChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import java.beans.PropertyChangeEvent;
import java.util.List;
import java.util.Set;

import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getDynamicJdbcConfig;


/**
 * An {@link ApplicationListener} listening on {@link ConfigPropertiesChangedEvent} or {@link ZoneContextChangedEvent}
 * to propagate the {@link DynamicJdbcConfigChangedEvent}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ConfigPropertiesChangedEvent
 * @see ZoneContextChangedEvent
 * @see DynamicJdbcConfigChangedEvent
 * @since 1.0.0
 */
class PropagatingDynamicJdbcConfigChangedEventListener implements SmartApplicationListener {

    private static final Logger logger = LoggerFactory.getLogger(PropagatingDynamicJdbcConfigChangedEventListener.class);

    private final Set<String> dynamicJdbcConfigPropertyNames;

    private final ConfigurableApplicationContext context;

    private final ConfigurableEnvironment environment;

    public PropagatingDynamicJdbcConfigChangedEventListener(Set<String> dynamicJdbcConfigPropertyNames, ConfigurableApplicationContext context) {
        this.dynamicJdbcConfigPropertyNames = dynamicJdbcConfigPropertyNames;
        this.context = context;
        this.environment = context.getEnvironment();
        logger.info("Context[id : '{}'] associates the property names of DynamicJdbcConfig : {}", context.getId(), dynamicJdbcConfigPropertyNames);
    }

    @Override
    public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
        return eventType.equals(ConfigPropertiesChangedEvent.class) || eventType.equals(ZoneContextChangedEvent.class);
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        logger.info("Context[id : '{}'] receives event : {}", context.getId(), event);
        if (event instanceof ConfigPropertiesChangedEvent) {
            onPropertySourcesChangedEvent((ConfigPropertiesChangedEvent) event);
        } else if (event instanceof ZoneContextChangedEvent) {
            onZoneContextChangedEvent((ZoneContextChangedEvent) event);
        }
    }

    private void onPropertySourcesChangedEvent(ConfigPropertiesChangedEvent event) {
        Set<String> changedPropertyNames = event.getChangedPropertyNames();
        for (String key : changedPropertyNames) {
            if (dynamicJdbcConfigPropertyNames.contains(key)) {
                logger.info("The key['{}'] of ConfigPropertiesChangedEvent[context : '{}'] matches the property names of DynamicJdbcConfig : {}", key,
                        context.getId(), dynamicJdbcConfigPropertyNames);
                publishDynamicJdbcConfigChangedEvent(key);
            }
        }
    }

    private void onZoneContextChangedEvent(ZoneContextChangedEvent event) {
        if (isZoneChanged(event)) {
            // Publish DynamicJdbcConfigChangedEvent if the DynamicJdbcConfig has the High Availability
            // DataSource
            dynamicJdbcConfigPropertyNames.forEach(propertyName -> {
                DynamicJdbcConfig dynamicJdbcConfig = getDynamicJdbcConfig(environment, propertyName);
                if (dynamicJdbcConfig.hasHighAvailabilityDataSource()) {
                    publishDynamicJdbcConfigChangedEvent(dynamicJdbcConfig, propertyName);
                }
            });
        }
    }

    private boolean isZoneChanged(ZoneContextChangedEvent event) {
        boolean zoneChanged = false;
        List<PropertyChangeEvent> propertyChangeEvents = event.getPropertyChangeEvents();
        for (PropertyChangeEvent propertyChangeEvent : propertyChangeEvents) {
            if ("zone".equals(propertyChangeEvent.getPropertyName())) {
                zoneChanged = true;
                break;
            }
        }
        return zoneChanged;
    }

    private void publishDynamicJdbcConfigChangedEvent(String propertyName) {
        DynamicJdbcConfig dynamicJdbcConfig = getDynamicJdbcConfig(environment, propertyName);
        publishDynamicJdbcConfigChangedEvent(dynamicJdbcConfig, propertyName);
    }

    private void publishDynamicJdbcConfigChangedEvent(DynamicJdbcConfig dynamicJdbcConfig, String propertyName) {
        context.publishEvent(new DynamicJdbcConfigChangedEvent(context, dynamicJdbcConfig, propertyName));
        logger.info("Context[id : '{}']  published a DynamicJdbcConfigChangedEvent[ property name : {}]", context.getId(), propertyName);
    }
}
