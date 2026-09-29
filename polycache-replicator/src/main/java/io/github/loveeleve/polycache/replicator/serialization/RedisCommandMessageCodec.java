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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A compact binary codec of {@link RedisCommandMessage}, used as the Kafka payload format.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class RedisCommandMessageCodec {

    /**
     * The version of the payload format.
     */
    public static final byte VERSION = 1;

    private RedisCommandMessageCodec() {
    }

    /**
     * Encodes the given message into its binary representation.
     *
     * @param message the message
     * @return the encoded bytes
     */
    public static byte[] encode(RedisCommandMessage message) {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(256);
            try (DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream)) {
                outputStream.writeByte(VERSION);
                outputStream.writeUTF(message.getCommandName());
                byte[][] args = message.getArgs();
                outputStream.writeInt(args.length);
                for (byte[] arg : args) {
                    writeNullableBytes(outputStream, arg);
                }
                writeNullableUTF(outputStream, message.getKey());
                writeNullableBytes(outputStream, message.getKeyBytes());
                writeNullableUTF(outputStream, message.getSource());
                outputStream.writeLong(message.getTimestamp());
            }
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Decodes the given binary representation into a message.
     *
     * @param bytes the encoded bytes
     * @return the decoded message
     */
    public static RedisCommandMessage decode(byte[] bytes) {
        try (DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = inputStream.readByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported version of the Redis command message : " + version);
            }
            String commandName = inputStream.readUTF();
            int argumentCount = inputStream.readInt();
            byte[][] args = new byte[argumentCount][];
            for (int i = 0; i < argumentCount; i++) {
                args[i] = readNullableBytes(inputStream);
            }
            return RedisCommandMessage.builder()
                    .commandName(commandName)
                    .args(args)
                    .key(readNullableUTF(inputStream))
                    .keyBytes(readNullableBytes(inputStream))
                    .source(readNullableUTF(inputStream))
                    .timestamp(inputStream.readLong())
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeNullableUTF(DataOutputStream outputStream, String value) throws IOException {
        outputStream.writeBoolean(value != null);
        if (value != null) {
            outputStream.writeUTF(value);
        }
    }

    private static String readNullableUTF(DataInputStream inputStream) throws IOException {
        return inputStream.readBoolean() ? inputStream.readUTF() : null;
    }

    private static void writeNullableBytes(DataOutputStream outputStream, byte[] value) throws IOException {
        int length = value == null ? -1 : value.length;
        outputStream.writeInt(length);
        if (value != null) {
            outputStream.write(value);
        }
    }

    private static byte[] readNullableBytes(DataInputStream inputStream) throws IOException {
        int length = inputStream.readInt();
        if (length < 0) {
            return null;
        }
        byte[] bytes = new byte[length];
        inputStream.readFully(bytes);
        return bytes;
    }
}
