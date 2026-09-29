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

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * The message of a captured Redis command, carrying the command name, the wire-ready arguments,
 * the routing key and the source of the write. It is the payload replicated across the data
 * centers.
 *
 * <p>The {@link #getArgs() args} are already encoded by the codec of the source client, so the
 * consumer side can replay the command with the byte-array codec without depending on the codec
 * of the source application.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   RedisCommandMessage message = RedisCommandMessage.builder()
 *           .commandName("SET")
 *           .args(new byte[][]{"user:1".getBytes(), "alice".getBytes()})
 *           .key("user:1")
 *           .source("zone-a")
 *           .build();
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public final class RedisCommandMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The name of the Redis command, e.g. {@code "SET"}.
     */
    private final String commandName;

    /**
     * The encoded arguments of the command, in the order of the Redis command line.
     */
    private final byte[][] args;

    /**
     * The routing key as a {@link String}, {@code null} if the key is not textual.
     */
    private final String key;

    /**
     * The routing key as bytes, {@code null} if the key is textual.
     */
    private final byte[] keyBytes;

    /**
     * The source (e.g. the name of the data center) that produced the command.
     */
    private final String source;

    /**
     * The time when the command was captured, in milliseconds.
     */
    private final long timestamp;

    private RedisCommandMessage(Builder builder) {
        this.commandName = Objects.requireNonNull(builder.commandName, "The command name must not be null");
        this.args = builder.args == null ? new byte[0][] : builder.args;
        this.key = builder.key;
        this.keyBytes = builder.keyBytes;
        this.source = builder.source;
        this.timestamp = builder.timestamp;
    }

    public String getCommandName() {
        return commandName;
    }

    public byte[][] getArgs() {
        return args;
    }

    public String getKey() {
        return key;
    }

    public byte[] getKeyBytes() {
        return keyBytes;
    }

    public String getSource() {
        return source;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * @return the argument count of the command
     */
    public int getArgumentCount() {
        return args.length;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The builder of {@link RedisCommandMessage}.
     */
    public static class Builder {

        private String commandName;

        private byte[][] args;

        private String key;

        private byte[] keyBytes;

        private String source;

        private long timestamp = System.currentTimeMillis();

        public Builder commandName(String commandName) {
            this.commandName = commandName;
            return this;
        }

        public Builder args(byte[][] args) {
            this.args = args;
            return this;
        }

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder keyBytes(byte[] keyBytes) {
            this.keyBytes = keyBytes;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public RedisCommandMessage build() {
            return new RedisCommandMessage(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RedisCommandMessage)) {
            return false;
        }
        RedisCommandMessage that = (RedisCommandMessage) o;
        return timestamp == that.timestamp
                && commandName.equals(that.commandName)
                && Arrays.deepEquals(args, that.args)
                && Objects.equals(key, that.key)
                && Arrays.equals(keyBytes, that.keyBytes)
                && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(commandName, key, source, timestamp);
        result = 31 * result + Arrays.deepHashCode(args);
        result = 31 * result + Arrays.hashCode(keyBytes);
        return result;
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", "RedisCommandMessage{", "}");
        joiner.add("commandName='" + commandName + "'");
        joiner.add("argumentCount=" + args.length);
        joiner.add("key='" + key + "'");
        joiner.add("source='" + source + "'");
        joiner.add("timestamp=" + timestamp);
        return joiner.toString();
    }
}
