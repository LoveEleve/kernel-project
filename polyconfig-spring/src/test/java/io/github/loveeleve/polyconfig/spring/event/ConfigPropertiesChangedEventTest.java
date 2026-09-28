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

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ConfigPropertiesChangedEvent}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ConfigPropertiesChangedEventTest {

    @Test
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigPropertiesChangedEvent(this, "", List.of(change("a", 1, 2))));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigPropertiesChangedEvent(this, "environment", List.of()));
    }

    @Test
    void testGetters() {
        ConfigPropertyChangedEvent first = change("app.name", "old", "new");
        ConfigPropertyChangedEvent second = change("app.port", 8080, 9090);
        ConfigPropertiesChangedEvent event = new ConfigPropertiesChangedEvent(this, "nacos",
                Arrays.asList(first, second));

        assertEquals("nacos", event.getSourceName());
        assertEquals(2, event.getChanges().size());
        assertEquals(2, event.getChangedPropertyNames().size());
        assertTrue(event.getChangedPropertyNames().contains("app.name"));
        assertTrue(event.getChangedPropertyNames().contains("app.port"));
    }

    @Test
    void testGetOldAndNewProperties() {
        ConfigPropertiesChangedEvent event = new ConfigPropertiesChangedEvent(this, "environment",
                Arrays.asList(change("app.name", "old", "new"), change("app.port", null, 8080)));

        assertEquals("new", event.getNewProperties().get("app.name"));
        assertEquals(8080, event.getNewProperties().get("app.port"));
        assertEquals("old", event.getOldProperties().get("app.name"));
        assertNull(event.getOldProperties().get("app.port"));
    }

    @Test
    void testIsChangedAndGetChange() {
        ConfigPropertiesChangedEvent event = new ConfigPropertiesChangedEvent(this, "environment",
                List.of(change("app.name", "old", "new")));

        assertTrue(event.isChanged("app.name"));
        assertFalse(event.isChanged("app.port"));
        assertEquals("new", event.getChange("app.name").getNewValue());
        assertNull(event.getChange("app.port"));
    }

    @Test
    void testChangesAreUnmodifiable() {
        ConfigPropertiesChangedEvent event = new ConfigPropertiesChangedEvent(this, "environment",
                List.of(change("app.name", "old", "new")));
        assertThrows(UnsupportedOperationException.class,
                () -> event.getChanges().add(change("x", 1, 2)));
    }

    private static ConfigPropertyChangedEvent change(String name, Object oldValue, Object newValue) {
        return new ConfigPropertyChangedEvent(name, oldValue, newValue);
    }
}
