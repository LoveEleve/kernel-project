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
import io.netty.buffer.ByteBufUtil;
import org.redisson.client.ChannelName;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Factory that converts the outbound Redisson {@code CommandData} into the wire-ready
 * {@link RedisCommandMessage}. The argument encoding mirrors the behavior of the Redisson
 * {@code CommandEncoder}:
 *
 * <ul>
 * <li>{@code byte[]} and {@link ByteBuf} arguments (already encoded by the client codec) are copied
 * as-is</li>
 * <li>{@link ChannelName} arguments use the raw channel name bytes</li>
 * <li>any other argument (e.g. the textual key, the number of keys of {@code EVAL}) is converted
 * by its {@code toString()} representation in UTF-8</li>
 * </ul>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class RedisCommandMessages {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private static final String EVAL = "EVAL";

    private static final String EVALSHA = "EVALSHA";

    private RedisCommandMessages() {
    }

    /**
     * Creates a {@link RedisCommandMessage} from the raw command name and arguments of the
     * Redisson {@code CommandData}.
     *
     * @param commandName the name of the Redis command
     * @param params      the raw arguments of the command
     * @param source      the source that produced the command
     * @return a non-null {@link RedisCommandMessage}
     */
    public static RedisCommandMessage message(String commandName, Object[] params, String source) {
        byte[][] args = encodeParams(params);
        RedisCommandMessage.Builder builder = RedisCommandMessage.builder()
                .commandName(commandName)
                .args(args)
                .source(source);
        Object key = findKey(commandName, params);
        if (key instanceof String) {
            builder.key((String) key);
        } else if (key != null) {
            builder.keyBytes(encode(key));
        }
        return builder.build();
    }

    /**
     * Encodes the arguments of a Redis command into their wire representation.
     *
     * @param params the raw arguments
     * @return the encoded arguments, never {@code null}
     */
    public static byte[][] encodeParams(Object[] params) {
        if (params == null || params.length == 0) {
            return new byte[0][];
        }
        byte[][] args = new byte[params.length][];
        for (int i = 0; i < params.length; i++) {
            args[i] = encode(params[i]);
        }
        return args;
    }

    /**
     * Encodes a single argument of a Redis command into its wire representation.
     *
     * @param param the raw argument
     * @return the encoded argument, never {@code null}
     */
    public static byte[] encode(Object param) {
        if (param == null) {
            return EMPTY_BYTES;
        }
        if (param instanceof byte[]) {
            return Arrays.copyOf((byte[]) param, ((byte[]) param).length);
        }
        if (param instanceof ByteBuf) {
            return ByteBufUtil.getBytes((ByteBuf) param);
        }
        if (param instanceof ChannelName) {
            byte[] name = ((ChannelName) param).getName();
            return Arrays.copyOf(name, name.length);
        }
        return param.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Finds the routing key of the command. For most commands it is the first argument; for
     * {@code EVAL} / {@code EVALSHA} it is the first key declared by the number of keys argument.
     *
     * @param commandName the name of the Redis command
     * @param params      the raw arguments of the command
     * @return the raw key argument, or {@code null} if the command has no key
     */
    public static Object findKey(String commandName, Object[] params) {
        if (params == null || params.length == 0) {
            return null;
        }
        if (EVAL.equals(commandName) || EVALSHA.equals(commandName)) {
            if (params.length > 2 && params[1] instanceof Number && ((Number) params[1]).intValue() > 0) {
                return params[2];
            }
            return null;
        }
        Object key = params[0];
        return isKeyType(key) ? key : null;
    }

    private static boolean isKeyType(Object key) {
        return key instanceof String || key instanceof byte[] || key instanceof ByteBuf || key instanceof ChannelName;
    }
}
