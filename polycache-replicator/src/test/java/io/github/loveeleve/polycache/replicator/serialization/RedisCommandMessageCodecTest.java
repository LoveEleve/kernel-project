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
package io.github.loveeleve.polycache.replicator.serialization;

import io.github.loveeleve.polycache.command.RedisCommandMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RedisCommandMessageCodec} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class RedisCommandMessageCodecTest {

    @Test
    void testRoundTrip() {
        RedisCommandMessage message = RedisCommandMessage.builder()
                .commandName("SET")
                .args(new byte[][]{bytes("user:1"), bytes("alice")})
                .key("user:1")
                .keyBytes(bytes("user:1"))
                .source("zone-a")
                .timestamp(123456789L)
                .build();

        RedisCommandMessage decoded = RedisCommandMessageCodec.decode(RedisCommandMessageCodec.encode(message));
        assertEquals(message, decoded);
    }

    @Test
    void testRoundTripWithNullFields() {
        RedisCommandMessage message = RedisCommandMessage.builder()
                .commandName("INCR")
                .args(new byte[][]{bytes("counter")})
                .timestamp(1L)
                .build();

        RedisCommandMessage decoded = RedisCommandMessageCodec.decode(RedisCommandMessageCodec.encode(message));
        assertEquals(message, decoded);
        assertNull(decoded.getKey());
        assertNull(decoded.getKeyBytes());
        assertNull(decoded.getSource());
    }

    @Test
    void testSerializeAndDeserialize() {
        RedisCommandMessageSerializer serializer = new RedisCommandMessageSerializer();
        RedisCommandMessageDeserializer deserializer = new RedisCommandMessageDeserializer();
        RedisCommandMessage message = RedisCommandMessage.builder()
                .commandName("EVAL")
                .args(new byte[][]{bytes("return 1"), bytes("1"), bytes("map:1")})
                .keyBytes(bytes("map:1"))
                .source("zone-b")
                .timestamp(2L)
                .build();

        assertEquals(message, deserializer.deserialize("topic", serializer.serialize("topic", message)));
        assertNull(serializer.serialize("topic", null));
        assertNull(deserializer.deserialize("topic", null));
    }

    @Test
    void testRoundTripWithLargeKey() {
        // A Redis key may exceed the 64KB limit of DataOutputStream#writeUTF
        String largeKey = "key:".repeat(20_000);
        RedisCommandMessage message = RedisCommandMessage.builder()
                .commandName("SET")
                .args(new byte[][]{largeKey.getBytes(StandardCharsets.UTF_8), bytes("alice")})
                .key(largeKey)
                .source("zone-a")
                .timestamp(1L)
                .build();

        assertEquals(message, RedisCommandMessageCodec.decode(RedisCommandMessageCodec.encode(message)));
    }

    @Test
    void testDecodeCorruptedArgumentCount() throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream)) {
            outputStream.writeByte(RedisCommandMessageCodec.VERSION);
            outputStream.writeInt(3);
            outputStream.write(bytes("SET"));
            outputStream.writeInt(-5);
        }
        assertThrows(UncheckedIOException.class, () -> RedisCommandMessageCodec.decode(byteArrayOutputStream.toByteArray()));
    }

    @Test
    void testDecodeCorruptedArgumentLength() throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream)) {
            outputStream.writeByte(RedisCommandMessageCodec.VERSION);
            outputStream.writeInt(3);
            outputStream.write(bytes("SET"));
            outputStream.writeInt(1);
            outputStream.writeInt(Integer.MAX_VALUE);
            outputStream.write('x');
        }
        assertThrows(UncheckedIOException.class, () -> RedisCommandMessageCodec.decode(byteArrayOutputStream.toByteArray()));
    }

    @Test
    void testUnsupportedVersion() {
        assertThrows(IllegalArgumentException.class, () -> RedisCommandMessageCodec.decode(new byte[]{9, 0, 0, 0}));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
