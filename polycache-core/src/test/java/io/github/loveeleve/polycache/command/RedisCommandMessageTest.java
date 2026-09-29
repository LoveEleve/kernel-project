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

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisCommandMessage} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class RedisCommandMessageTest {

    private static final byte[] KEY = "user:1".getBytes(StandardCharsets.UTF_8);

    private static final byte[] VALUE = "alice".getBytes(StandardCharsets.UTF_8);

    @Test
    void testBuilder() {
        long timestamp = System.currentTimeMillis();
        RedisCommandMessage message = RedisCommandMessage.builder()
                .commandName("SET")
                .args(new byte[][]{KEY, VALUE})
                .key("user:1")
                .source("zone-a")
                .timestamp(timestamp)
                .build();

        assertEquals("SET", message.getCommandName());
        assertEquals(2, message.getArgumentCount());
        assertArrayEquals(KEY, message.getArgs()[0]);
        assertArrayEquals(VALUE, message.getArgs()[1]);
        assertEquals("user:1", message.getKey());
        assertNull(message.getKeyBytes());
        assertEquals("zone-a", message.getSource());
        assertEquals(timestamp, message.getTimestamp());
    }

    @Test
    void testDefaultValues() {
        RedisCommandMessage message = RedisCommandMessage.builder()
                .commandName("SET")
                .build();

        assertEquals(0, message.getArgumentCount());
        assertNull(message.getKey());
        assertNull(message.getKeyBytes());
        assertNull(message.getSource());
        assertTrue(message.getTimestamp() > 0);
    }

    @Test
    void testCommandNameRequired() {
        assertThrows(NullPointerException.class, () -> RedisCommandMessage.builder().build());
    }

    @Test
    void testEqualsAndHashCode() {
        RedisCommandMessage message = message();
        RedisCommandMessage same = message();
        same = RedisCommandMessage.builder()
                .commandName(same.getCommandName())
                .args(same.getArgs())
                .key(same.getKey())
                .source(same.getSource())
                .timestamp(same.getTimestamp())
                .build();

        assertEquals(message, same);
        assertEquals(message.hashCode(), same.hashCode());

        RedisCommandMessage different = RedisCommandMessage.builder()
                .commandName("SET")
                .args(new byte[][]{KEY, "bob".getBytes(StandardCharsets.UTF_8)})
                .key("user:1")
                .source("zone-a")
                .timestamp(message.getTimestamp())
                .build();

        assertNotEquals(message, different);
    }

    @Test
    void testToString() {
        String toString = message().toString();
        assertTrue(toString.contains("commandName='SET'"));
        assertTrue(toString.contains("argumentCount=2"));
        assertTrue(toString.contains("source='zone-a'"));
    }

    private static RedisCommandMessage message() {
        return RedisCommandMessage.builder()
                .commandName("SET")
                .args(new byte[][]{KEY, VALUE})
                .key("user:1")
                .source("zone-a")
                .timestamp(1000L)
                .build();
    }
}
