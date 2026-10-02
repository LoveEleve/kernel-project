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
package io.github.loveeleve.polycache.command;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisCommandRegistry} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class RedisCommandRegistryTest {

    @Test
    void testGetCommands() {
        assertFalse(RedisCommandRegistry.getCommands().isEmpty());
        assertTrue(RedisCommandRegistry.getCommands().contains("SET"));
        assertTrue(RedisCommandRegistry.getCommands().contains("GET"));
        assertThrows(UnsupportedOperationException.class, () -> RedisCommandRegistry.getCommands().add("FOO"));
    }

    @Test
    void testGetWriteCommands() {
        assertFalse(RedisCommandRegistry.getWriteCommands().isEmpty());
        assertTrue(RedisCommandRegistry.getWriteCommands().contains("SET"));
        assertFalse(RedisCommandRegistry.getWriteCommands().contains("GET"));
    }

    @Test
    void testIsCommand() {
        assertTrue(RedisCommandRegistry.isCommand("SET"));
        assertTrue(RedisCommandRegistry.isCommand("set"));
        assertTrue(RedisCommandRegistry.isCommand("GET"));
        assertFalse(RedisCommandRegistry.isCommand("NOT-A-COMMAND"));
        assertFalse(RedisCommandRegistry.isCommand(null));
    }

    @Test
    void testIsWriteCommand() {
        assertTrue(RedisCommandRegistry.isWriteCommand("SET"));
        assertTrue(RedisCommandRegistry.isWriteCommand("set"));
        assertTrue(RedisCommandRegistry.isWriteCommand("HSET"));
        assertFalse(RedisCommandRegistry.isWriteCommand("GET"));
        assertFalse(RedisCommandRegistry.isWriteCommand("NOT-A-COMMAND"));
        assertFalse(RedisCommandRegistry.isWriteCommand(null));
    }

    @Test
    void testCaseInsensitivityIsLocaleIndependent() {
        Locale defaultLocale = Locale.getDefault();
        try {
            // In the Turkish locale, "incr".toUpperCase() produces the dotted capital I
            Locale.setDefault(new Locale("tr", "TR"));
            assertTrue(RedisCommandRegistry.isCommand("set"));
            assertTrue(RedisCommandRegistry.isWriteCommand("incr"));
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }
}
