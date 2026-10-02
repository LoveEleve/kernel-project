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
package io.github.loveeleve.polyzone.spring.cloud.event;

import io.github.loveeleve.polyzone.ZoneContext;
import io.github.loveeleve.polyzone.spring.CompositeZoneLocator;
import io.github.loveeleve.polyzone.spring.DefaultZoneLocator;
import io.github.loveeleve.polyzone.spring.ZoneLocator;
import io.github.loveeleve.polyzone.spring.event.ZoneContextChangedEvent;
import io.github.loveeleve.polyzone.spring.event.ZoneContextChangedListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.loveeleve.polyzone.ZoneConstants.ZONE_PROPERTY_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the configuration-center driven zone switch chain:
 * <pre>
 * EnvironmentChangeEvent (config center refresh)
 *   -&gt; ZoneContextChangedListener reads the latest properties
 *     -&gt; ZoneContext.setZone(...)
 *       -&gt; ZoneContextChangedEvent (for downstream components)
 * </pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ZoneContextChangedListenerConfigCenterTest {

    private ZoneContext zoneContext;

    @BeforeEach
    void setUp() {
        zoneContext = ZoneContext.get();
        zoneContext.reset();
    }

    @AfterEach
    void tearDown() {
        zoneContext.reset();
    }

    @Test
    void testEnvironmentChangeEventTriggersZoneSwitch() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("zoneContext", zoneContext);
            ZoneLocator zoneLocator = new CompositeZoneLocator(java.util.Collections.singletonList(
                    new DefaultZoneLocator()));
            context.getBeanFactory().registerSingleton("zoneLocator", zoneLocator);

            Map<String, Object> properties = new HashMap<>();
            properties.put(ZONE_PROPERTY_NAME, "zone-a");
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("config-center", properties));

            context.refresh();

            ZoneContextChangedListener listener = new ZoneContextChangedListener();
            listener.setEnvironment(context.getEnvironment());
            listener.setApplicationContext(context);
            context.addApplicationListener(listener);

            AtomicBoolean zoneContextChangedEventReceived = new AtomicBoolean(false);
            context.addApplicationListener(event -> {
                if (event instanceof ZoneContextChangedEvent) {
                    zoneContextChangedEventReceived.set(true);
                }
            });

            // The initial synchronization, like the ApplicationStartedEvent at startup
            context.publishEvent(new EnvironmentChangeEvent(Set.of(ZONE_PROPERTY_NAME)));
            assertEquals("zone-a", zoneContext.getZone());

            // Simulate a configuration center push
            properties.put(ZONE_PROPERTY_NAME, "zone-b");
            context.publishEvent(new EnvironmentChangeEvent(Set.of(ZONE_PROPERTY_NAME)));

            assertEquals("zone-b", zoneContext.getZone());
            assertTrue(zoneContextChangedEventReceived.get());
        }
    }

    @Test
    void testRevertToOriginalZoneAfterFailover() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("zoneContext", zoneContext);
            // The "original" zone is auto-detected from the property "polyzone.zone"
            ZoneLocator zoneLocator = new CompositeZoneLocator(java.util.Collections.singletonList(
                    new DefaultZoneLocator()));
            context.getBeanFactory().registerSingleton("zoneLocator", zoneLocator);

            Map<String, Object> properties = new HashMap<>();
            properties.put(ZONE_PROPERTY_NAME, "zone-a");
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("config-center", properties));

            context.refresh();

            ZoneContextChangedListener listener = new ZoneContextChangedListener();
            listener.setEnvironment(context.getEnvironment());
            listener.setApplicationContext(context);
            context.addApplicationListener(listener);

            // The initial synchronization, like the ApplicationStartedEvent at startup
            context.publishEvent(new EnvironmentChangeEvent(Set.of(ZONE_PROPERTY_NAME)));
            assertEquals("zone-a", zoneContext.getZone());

            // Failover: switch to another zone
            properties.put(ZONE_PROPERTY_NAME, "zone-b");
            context.publishEvent(new EnvironmentChangeEvent(Set.of(ZONE_PROPERTY_NAME)));
            assertEquals("zone-b", zoneContext.getZone());

            // Failback: revert to the auto-detected (original) zone
            properties.put(ZONE_PROPERTY_NAME, "originalZone");
            context.publishEvent(new EnvironmentChangeEvent(Set.of(ZONE_PROPERTY_NAME)));

            assertEquals("zone-a", zoneContext.getZone());
        }
    }
}
