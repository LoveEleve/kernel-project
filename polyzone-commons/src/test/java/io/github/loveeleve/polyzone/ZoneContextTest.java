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
package io.github.loveeleve.polyzone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import static io.github.loveeleve.polyzone.ZoneConstants.CURRENT_ZONE_PROPERTY_NAME;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE_ENABLED;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE_PREFERENCE_ENABLED;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE_PREFERENCE_FILTER_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ZoneContext}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ZoneContextTest {

    private ZoneContext zoneContext;

    @BeforeEach
    void setUp() {
        zoneContext = ZoneContext.get();
        zoneContext.reset();
    }

    @AfterEach
    void tearDown() {
        zoneContext.reset();
        System.clearProperty(CURRENT_ZONE_PROPERTY_NAME);
    }

    @Test
    void testGetSingletonInstance() {
        assertNotNull(ZoneContext.get());
        assertEquals(ZoneContext.get(), ZoneContext.get());
    }

    @Test
    void testDefaultValues() {
        assertEquals(DEFAULT_ZONE_ENABLED, zoneContext.isEnabled());
        assertEquals(DEFAULT_ZONE, zoneContext.getZone());
        assertEquals(DEFAULT_ZONE_PREFERENCE_ENABLED, zoneContext.isPreferenceEnabled());
        assertEquals(DEFAULT_ZONE_PREFERENCE_FILTER_ORDER, zoneContext.getPreferenceFilterOrder());
        assertEquals(DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE,
                zoneContext.getPreferenceUpstreamZoneReadyPercentage());
        assertEquals(DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE,
                zoneContext.getPreferenceUpstreamSameZoneMinAvailable());
        assertNull(zoneContext.getPreferenceUpstreamDisabledZone());
    }

    @Test
    void testSetEnabled() {
        zoneContext.setEnabled(false);
        assertFalse(zoneContext.isEnabled());

        zoneContext.setEnabled(true);
        assertTrue(zoneContext.isEnabled());
    }

    @Test
    void testSetEnabledNoChangeDoesNotFireEvent() {
        List<PropertyChangeEvent> events = new ArrayList<>();
        PropertyChangeListener listener = events::add;
        zoneContext.addPropertyChangeListener(listener);
        // Setting same value -> no event
        zoneContext.setEnabled(DEFAULT_ZONE_ENABLED);
        assertEquals(0, events.size());
        zoneContext.removePropertyChangeListener(listener);
    }

    @Test
    void testSetZone() {
        zoneContext.setZone("zone-a");
        assertEquals("zone-a", zoneContext.getZone());
    }

    @Test
    void testSetZoneTrimsWhitespace() {
        zoneContext.setZone("  zone-b  ");
        assertEquals("zone-b", zoneContext.getZone());
    }

    @Test
    void testSetZoneSameValueNoEvent() {
        zoneContext.setZone("zone-x");
        List<PropertyChangeEvent> events = new ArrayList<>();
        zoneContext.addPropertyChangeListener(events::add);
        zoneContext.setZone("zone-x");
        assertEquals(0, events.size());
    }

    @Test
    void testSetPreferenceEnabled() {
        zoneContext.setPreferenceEnabled(true);
        assertTrue(zoneContext.isPreferenceEnabled());

        zoneContext.setPreferenceEnabled(false);
        assertFalse(zoneContext.isPreferenceEnabled());
    }

    @Test
    void testSetPreferenceFilterOrder() {
        zoneContext.setPreferenceFilterOrder(5);
        assertEquals(5, zoneContext.getPreferenceFilterOrder());
    }

    @Test
    void testSetPreferenceUpstreamZoneReadyPercentage() {
        zoneContext.setPreferenceUpstreamZoneReadyPercentage(50);
        assertEquals(50, zoneContext.getPreferenceUpstreamZoneReadyPercentage());
    }

    @Test
    void testSetPreferenceUpstreamSameZoneMinAvailable() {
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(10);
        assertEquals(10, zoneContext.getPreferenceUpstreamSameZoneMinAvailable());
    }

    @Test
    void testSetPreferenceUpstreamDisabledZone() {
        zoneContext.setPreferenceUpstreamDisabledZone("zone-a,zone-b");
        assertEquals("zone-a,zone-b", zoneContext.getPreferenceUpstreamDisabledZone());
    }

    @Test
    void testSetPreferenceUpstreamDisabledZoneWithSpaces() {
        zoneContext.setPreferenceUpstreamDisabledZone(" zone-a , zone-b ");
        assertEquals("zone-a,zone-b", zoneContext.getPreferenceUpstreamDisabledZone());
    }

    @Test
    void testSetPreferenceUpstreamDisabledZoneBlank() {
        zoneContext.setPreferenceUpstreamDisabledZone(null);
        assertNull(zoneContext.getPreferenceUpstreamDisabledZone());
    }

    @Test
    void testSetPreferenceUpstreamDisabledZoneEmptyString() {
        // Set a non-null first, then set to blank string to trigger the branch
        zoneContext.setPreferenceUpstreamDisabledZone("zone-a");
        zoneContext.setPreferenceUpstreamDisabledZone("");
        assertEquals("", zoneContext.getPreferenceUpstreamDisabledZone());
    }

    @Test
    void testPropertyChangeListenerFired() {
        List<PropertyChangeEvent> events = new ArrayList<>();
        PropertyChangeListener listener = events::add;
        zoneContext.addPropertyChangeListener(listener);

        zoneContext.setZone("new-zone");

        assertEquals(1, events.size());
        assertEquals("zone", events.get(0).getPropertyName());
        assertEquals(DEFAULT_ZONE, events.get(0).getOldValue());
        assertEquals("new-zone", events.get(0).getNewValue());

        zoneContext.removePropertyChangeListener(listener);
    }

    @Test
    void testRemovePropertyChangeListener() {
        List<PropertyChangeEvent> events = new ArrayList<>();
        PropertyChangeListener listener = events::add;
        zoneContext.addPropertyChangeListener(listener);
        zoneContext.removePropertyChangeListener(listener);

        zoneContext.setZone("removed-zone");

        assertEquals(0, events.size());
    }

    @Test
    void testEnable_WhenAlreadyEnabled() {
        // Already enabled by default
        boolean previous = zoneContext.enable();
        assertTrue(previous); // was already enabled
        assertTrue(zoneContext.isEnabled());
    }

    @Test
    void testEnable_WhenDisabled() {
        zoneContext.setEnabled(false);
        boolean previous = zoneContext.enable();
        assertFalse(previous); // was disabled
        assertTrue(zoneContext.isEnabled());
    }

    @Test
    void testReset() {
        zoneContext.setEnabled(false);
        zoneContext.setZone("custom-zone");
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setPreferenceFilterOrder(99);
        zoneContext.setPreferenceUpstreamZoneReadyPercentage(50);
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(10);
        zoneContext.setPreferenceUpstreamDisabledZone("zone-c");

        zoneContext.reset();

        assertEquals(DEFAULT_ZONE_ENABLED, zoneContext.isEnabled());
        assertEquals(DEFAULT_ZONE, zoneContext.getZone());
        assertEquals(DEFAULT_ZONE_PREFERENCE_ENABLED, zoneContext.isPreferenceEnabled());
        assertEquals(DEFAULT_ZONE_PREFERENCE_FILTER_ORDER, zoneContext.getPreferenceFilterOrder());
        assertEquals(DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE,
                zoneContext.getPreferenceUpstreamZoneReadyPercentage());
        assertEquals(DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE,
                zoneContext.getPreferenceUpstreamSameZoneMinAvailable());
        assertNull(zoneContext.getPreferenceUpstreamDisabledZone());
    }

    @Test
    void testToString() {
        String str = zoneContext.toString();
        assertNotNull(str);
        assertTrue(str.contains("ZoneContext{"));
        assertTrue(str.contains("enabled="));
        assertTrue(str.contains("zone="));
    }

    @Test
    void testGetCurrentZoneFromSystemProperty() {
        System.setProperty(CURRENT_ZONE_PROPERTY_NAME, "sys-zone");
        assertEquals("sys-zone", ZoneContext.getCurrentZone());
    }

    @Test
    void testGetCurrentZoneDefault() {
        System.clearProperty(CURRENT_ZONE_PROPERTY_NAME);
        assertEquals(DEFAULT_ZONE, ZoneContext.getCurrentZone());
    }

    @Test
    void testZoneIsCommittedBeforePropertyChangeEventFired() {
        List<String> observedZones = new ArrayList<>();
        PropertyChangeListener listener = event -> {
            if ("zone".equals(event.getPropertyName())) {
                observedZones.add(zoneContext.getZone());
            }
        };
        zoneContext.addPropertyChangeListener(listener);

        zoneContext.setZone("zone-committed");

        assertEquals(1, observedZones.size());
        assertEquals("zone-committed", observedZones.get(0));
        zoneContext.removePropertyChangeListener(listener);
    }

    @Test
    void testCurrentZoneSystemPropertyIsUpdatedOnSetZone() {
        System.clearProperty(CURRENT_ZONE_PROPERTY_NAME);

        zoneContext.setZone("zone-sys");
        assertEquals("zone-sys", ZoneContext.getCurrentZone());

        zoneContext.setZone("  ");
        assertEquals(DEFAULT_ZONE, ZoneContext.getCurrentZone());
    }

    @Test
    void testPropertyChangeEventFiredForAllProperties() {
        List<PropertyChangeEvent> events = new ArrayList<>();
        PropertyChangeListener listener = events::add;
        zoneContext.addPropertyChangeListener(listener);

        zoneContext.setEnabled(false);
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setPreferenceFilterOrder(42);
        zoneContext.setPreferenceUpstreamZoneReadyPercentage(80);
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(3);
        zoneContext.setPreferenceUpstreamDisabledZone("zone-d");

        assertEquals(6, events.size());
        zoneContext.removePropertyChangeListener(listener);
    }
}
