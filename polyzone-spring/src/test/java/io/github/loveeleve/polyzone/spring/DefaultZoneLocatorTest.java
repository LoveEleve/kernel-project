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
package io.github.loveeleve.polyzone.spring;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE;
import static io.github.loveeleve.polyzone.ZoneConstants.ORIGINAL_ZONE;
import static io.github.loveeleve.polyzone.ZoneConstants.ZONE_PROPERTY_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultZoneLocator} and base class {@link AbstractZoneLocator}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class DefaultZoneLocatorTest {

    @Test
    void testDefaultOrder() {
        DefaultZoneLocator locator = new DefaultZoneLocator();
        assertEquals(DefaultZoneLocator.DEFAULT_ORDER, locator.getOrder());
        assertEquals(20, locator.getOrder());
    }

    @Test
    void testSetOrder() {
        DefaultZoneLocator locator = new DefaultZoneLocator();
        locator.setOrder(5);
        assertEquals(5, locator.getOrder());
    }

    @Test
    void testSupportsAlwaysTrue() {
        DefaultZoneLocator locator = new DefaultZoneLocator();
        assertTrue(locator.supports(new StandardEnvironment()));
    }

    @Test
    void testLocateWithZoneProperty() {
        DefaultZoneLocator locator = new DefaultZoneLocator();
        StandardEnvironment environment = new StandardEnvironment();

        Map<String, Object> props = new HashMap<>();
        props.put(ZONE_PROPERTY_NAME, "zone-a");
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(new MapPropertySource("test", props));

        String zone = locator.locate(environment);
        assertEquals("zone-a", zone);
    }

    @Test
    void testLocateWithoutZonePropertyFallsBackToDefault() {
        DefaultZoneLocator locator = new DefaultZoneLocator();
        StandardEnvironment environment = new StandardEnvironment();

        String zone = locator.locate(environment);
        assertEquals(DEFAULT_ZONE, zone);
    }

    @Test
    void testLocateWithOriginalZoneMarkerFallsBackToDefault() {
        DefaultZoneLocator locator = new DefaultZoneLocator();
        StandardEnvironment environment = new StandardEnvironment();

        Map<String, Object> props = new HashMap<>();
        props.put(ZONE_PROPERTY_NAME, ORIGINAL_ZONE);
        environment.getPropertySources().addFirst(new MapPropertySource("test", props));

        String zone = locator.locate(environment);
        assertEquals(DEFAULT_ZONE, zone);
    }

    @Test
    void testSetBeanName() {
        DefaultZoneLocator locator = new DefaultZoneLocator();
        locator.setBeanName("defaultZoneLocator");
        assertEquals("defaultZoneLocator", locator.beanName);
    }

    @Test
    void testToString() {
        DefaultZoneLocator locator = new DefaultZoneLocator();
        locator.setBeanName("defaultZoneLocator");
        String str = locator.toString();
        assertNotNull(str);
        assertTrue(str.contains("beanName='defaultZoneLocator'"));
        assertTrue(str.contains("order=20"));
    }

    @Test
    void testLoggerNotNull() {
        DefaultZoneLocator locator = new DefaultZoneLocator();
        assertNotNull(locator.logger);
    }
}
