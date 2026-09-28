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
package io.github.loveeleve.polyzone.spring.event;

import io.github.loveeleve.polyzone.ZoneContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;

import java.beans.PropertyChangeEvent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link ZoneContextChangedEvent}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ZoneContextChangedEventTest {

    @Test
    void testGetZoneContext() {
        ApplicationContext context = new StaticApplicationContext();
        ZoneContext zoneContext = ZoneContext.get();
        List<PropertyChangeEvent> events = Arrays.asList(
                new PropertyChangeEvent(zoneContext, "zone", "old-zone", "new-zone")
        );

        ZoneContextChangedEvent event = new ZoneContextChangedEvent(context, zoneContext, events);

        assertSame(zoneContext, event.getZoneContext());
    }

    @Test
    void testGetPropertyChangeEvents() {
        ApplicationContext context = new StaticApplicationContext();
        ZoneContext zoneContext = ZoneContext.get();
        PropertyChangeEvent pce1 = new PropertyChangeEvent(zoneContext, "zone", "old-zone", "new-zone");
        PropertyChangeEvent pce2 = new PropertyChangeEvent(zoneContext, "enabled", true, false);
        List<PropertyChangeEvent> events = Arrays.asList(pce1, pce2);

        ZoneContextChangedEvent event = new ZoneContextChangedEvent(context, zoneContext, events);

        List<PropertyChangeEvent> result = event.getPropertyChangeEvents();
        assertEquals(2, result.size());
        assertSame(pce1, result.get(0));
        assertSame(pce2, result.get(1));
    }

    @Test
    void testGetSource() {
        StaticApplicationContext context = new StaticApplicationContext();
        ZoneContext zoneContext = ZoneContext.get();
        List<PropertyChangeEvent> events = Arrays.asList(
                new PropertyChangeEvent(zoneContext, "zone", "a", "b")
        );

        ZoneContextChangedEvent event = new ZoneContextChangedEvent(context, zoneContext, events);
        assertSame(context, event.getSource());
        assertSame(context, event.getApplicationContext());
    }
}
