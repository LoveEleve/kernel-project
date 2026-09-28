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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static io.github.loveeleve.polyzone.ZoneConstants.ZONE_PROPERTY_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for {@link ZoneAttachmentHandler}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ZoneAttachmentHandlerTest {

    private ZoneContext zoneContext;
    private ZoneAttachmentHandler handler;

    @BeforeEach
    void setUp() {
        zoneContext = ZoneContext.get();
        zoneContext.reset();
        handler = new ZoneAttachmentHandler(zoneContext);
    }

    @AfterEach
    void tearDown() {
        zoneContext.reset();
    }

    @Test
    void testAttachZoneWithValidZone() {
        zoneContext.setZone("zone-a");
        Map<String, String> metadata = new HashMap<>();
        handler.attachZone(metadata);
        assertEquals("zone-a", metadata.get(ZONE_PROPERTY_NAME));
    }

    @Test
    void testAttachZoneWithBlankZone() {
        // defaultZone is non-blank, so set zone to blank
        zoneContext.setZone("  ");
        Map<String, String> metadata = new HashMap<>();
        handler.attachZone(metadata);
        // When zone is blank after trimming, it shouldn't be attached
        assertFalse(metadata.containsKey(ZONE_PROPERTY_NAME));
    }

    @Test
    void testAttachZoneWithDefaultZone() {
        // DEFAULT_ZONE ("defaultZone") is non-blank, so it should be attached
        Map<String, String> metadata = new HashMap<>();
        handler.attachZone(metadata);
        assertEquals("defaultZone", metadata.get(ZONE_PROPERTY_NAME));
    }

    @Test
    void testAttachZoneWithUnmodifiableMap() {
        zoneContext.setZone("zone-b");
        Map<String, String> unmodifiableMap = Collections.unmodifiableMap(new HashMap<>());
        // Should not throw; handler catches UnsupportedOperationException
        handler.attachZone(unmodifiableMap);
        assertFalse(unmodifiableMap.containsKey(ZONE_PROPERTY_NAME));
    }
}
