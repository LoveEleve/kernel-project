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
package io.github.loveeleve.polycache.redisson.capture;

import io.github.loveeleve.polycache.command.RedisCommandMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RedisCommandMessages} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class RedisCommandMessagesTest {

    @Test
    void testEncode() {
        assertArrayEquals(new byte[0], RedisCommandMessages.encode(null));
        assertArrayEquals(bytes("value"), RedisCommandMessages.encode(bytes("value")));
        assertArrayEquals(bytes("123"), RedisCommandMessages.encode(123));
        assertArrayEquals(bytes("name"), RedisCommandMessages.encode("name"));
    }

    @Test
    void testEncodeByteBufWithoutConsuming() {
        ByteBuf byteBuf = Unpooled.copiedBuffer(bytes("value"));
        assertArrayEquals(bytes("value"), RedisCommandMessages.encode(byteBuf));
        assertEquals(bytes("value").length, byteBuf.readableBytes());
    }

    @Test
    void testEncodeParams() {
        byte[][] args = RedisCommandMessages.encodeParams(new Object[]{"user:1", bytes("alice")});
        assertEquals(2, args.length);
        assertArrayEquals(bytes("user:1"), args[0]);
        assertArrayEquals(bytes("alice"), args[1]);
        assertEquals(0, RedisCommandMessages.encodeParams(null).length);
        assertEquals(0, RedisCommandMessages.encodeParams(new Object[0]).length);
    }

    @Test
    void testFindKey() {
        assertEquals("user:1", RedisCommandMessages.findKey("SET", new Object[]{"user:1", bytes("alice")}));
        assertArrayEquals(bytes("user:1"), (byte[]) RedisCommandMessages.findKey("SET", new Object[]{bytes("user:1")}));
        assertNull(RedisCommandMessages.findKey("SET", new Object[0]));
        assertNull(RedisCommandMessages.findKey("SET", null));
        assertNull(RedisCommandMessages.findKey("SET", new Object[]{123}));
    }

    @Test
    void testFindKeyOfEval() {
        assertNull(RedisCommandMessages.findKey("EVAL", new Object[]{"return 1", 0}));
        assertNull(RedisCommandMessages.findKey("EVAL", new Object[]{"return 1", 0, "ignored"}));
        assertEquals("map:1", RedisCommandMessages.findKey("EVAL", new Object[]{"return 1", 2, "map:1", "map:2"}));
        assertEquals("map:1", RedisCommandMessages.findKey("EVALSHA", new Object[]{"sha1", 1, "map:1"}));
    }

    @Test
    void testMessage() {
        RedisCommandMessage message = RedisCommandMessages.message("SET", new Object[]{"user:1", bytes("alice")}, "zone-a");
        assertEquals("SET", message.getCommandName());
        assertEquals("user:1", message.getKey());
        assertEquals("zone-a", message.getSource());
        assertEquals(2, message.getArgumentCount());

        RedisCommandMessage evalMessage = RedisCommandMessages.message("EVAL", new Object[]{"return 1", 1, bytes("map:1")}, "zone-a");
        assertArrayEquals(bytes("map:1"), evalMessage.getKeyBytes());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
