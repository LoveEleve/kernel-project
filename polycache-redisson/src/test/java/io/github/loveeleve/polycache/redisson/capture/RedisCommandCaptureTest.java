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
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.CommandData;
import org.redisson.client.protocol.CommandsData;
import org.redisson.client.protocol.RedisCommand;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisCommandCapture} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class RedisCommandCaptureTest {

    @Test
    void testCaptureWriteCommand() {
        List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
        RedisCommandCapture capture = capture(messages);

        CompletableFuture<Object> promise = new CompletableFuture<>();
        capture.capture(commandData(promise, "SET", "user:1", "alice"));
        promise.complete(null);

        assertEquals(1, messages.size());
        RedisCommandMessage message = messages.get(0);
        assertEquals("SET", message.getCommandName());
        assertEquals("user:1", message.getKey());
        assertEquals("zone-a", message.getSource());
    }

    @Test
    void testCaptureReadCommandIsSkipped() {
        List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
        RedisCommandCapture capture = capture(messages);

        CompletableFuture<Object> promise = new CompletableFuture<>();
        capture.capture(commandData(promise, "GET", "user:1"));
        promise.complete(null);

        assertTrue(messages.isEmpty());
    }

    @Test
    void testFailedCommandIsNotCaptured() {
        List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
        RedisCommandCapture capture = capture(messages);

        CompletableFuture<Object> promise = new CompletableFuture<>();
        capture.capture(commandData(promise, "SET", "user:1", "alice"));
        promise.completeExceptionally(new IllegalStateException("Boom"));

        assertTrue(messages.isEmpty());
    }

    @Test
    void testRetriedCommandIsCapturedOnce() {
        List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
        RedisCommandCapture capture = capture(messages);

        CompletableFuture<Object> promise = new CompletableFuture<>();
        CommandData commandData = commandData(promise, "SET", "user:1", "alice");
        // The Netty write path may retry the same command
        capture.capture(commandData);
        capture.capture(commandData);
        promise.complete(null);

        assertEquals(1, messages.size());
    }

    private static RedisCommandCapture capture(List<RedisCommandMessage> messages) {
        return RedisCommandCapture.builder()
                .source("zone-a")
                .listener(messages::add)
                .build();
    }

    /**
     * With a batch executed with {@code skipResult}, Redisson does not complete the promises of the
     * inner commands, so the commands must be published when the batch completes.
     */
    @Test
    void testSkipResultBatchIsCapturedOnBatchCompletion() {
        List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
        RedisCommandCapture capture = capture(messages);

        CompletableFuture<Void> batchPromise = new CompletableFuture<>();
        CommandData<?, ?> innerClient = commandData(new CompletableFuture<>(), "CLIENT", "REPLY", "OFF");
        CommandData<?, ?> innerSet = commandData(new CompletableFuture<>(), "SET", "skip:1", "value");
        CommandsData commandsData = new CommandsData(batchPromise, List.of(innerClient, innerSet),
                true, false, false, false);

        capture.capture(commandsData);
        assertTrue(messages.isEmpty());

        batchPromise.complete(null);
        assertEquals(1, messages.size());
        assertEquals("SET", messages.get(0).getCommandName());
        assertEquals("skip:1", messages.get(0).getKey());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CommandData<?, ?> commandData(CompletableFuture<Object> promise, String commandName, Object... params) {
        return new CommandData(promise, StringCodec.INSTANCE, new RedisCommand<>(commandName), params);
    }

    /**
     * The capture handler must never affect the Redis command itself.
     */
    @Test
    void testCaptureFailureDoesNotAffectTheWrite() {
        RedisCommandCapture capture = RedisCommandCapture.builder()
                .commandFilter(commandName -> {
                    throw new IllegalStateException("Boom");
                })
                .listener(message -> {
                })
                .build();

        EmbeddedChannel channel = new EmbeddedChannel(new CommandCaptureHandler(capture));
        try {
            Object message = commandData(new CompletableFuture<>(), "SET", "user:1", "alice");
            assertTrue(channel.writeOutbound(message));
            assertEquals(message, channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
