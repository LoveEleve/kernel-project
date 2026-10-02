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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    @Test
    void testInterceptorsRunOncePerCommandOnRetry() {
        List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
        AtomicInteger invocations = new AtomicInteger();
        RedisCommandCapture capture = RedisCommandCapture.builder()
                .source("zone-a")
                .listener(messages::add)
                .interceptor(message -> {
                    invocations.incrementAndGet();
                    return message;
                })
                .build();

        CompletableFuture<Object> promise = new CompletableFuture<>();
        CommandData<?, ?> commandData = commandData(promise, "SET", "user:1", "alice");
        // The Netty write path may retry the same command
        capture.capture(commandData);
        capture.capture(commandData);
        promise.complete(null);

        assertEquals(1, invocations.get());
        assertEquals(1, messages.size());
    }

    @Test
    void testInterceptorsFilterAndRewrite() {
        List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
        RedisCommandCapture capture = RedisCommandCapture.builder()
                .source("zone-a")
                .listener(messages::add)
                // The temporary keys are not replicated
                .interceptor(message -> message.getKey() != null && message.getKey().startsWith("temp:") ? null : message)
                // The published message is rewritten
                .interceptor(message -> RedisCommandMessage.builder()
                        .commandName(message.getCommandName())
                        .args(message.getArgs())
                        .key(message.getKey())
                        .keyBytes(message.getKeyBytes())
                        .source("rewritten")
                        .timestamp(message.getTimestamp())
                        .build())
                .build();
        assertEquals(2, capture.getInterceptors().size());

        CompletableFuture<Object> temporaryPromise = new CompletableFuture<>();
        capture.capture(commandData(temporaryPromise, "SET", "temp:1", "value"));
        temporaryPromise.complete(null);
        assertTrue(messages.isEmpty());

        CompletableFuture<Object> keptPromise = new CompletableFuture<>();
        capture.capture(commandData(keptPromise, "SET", "user:1", "value"));
        keptPromise.complete(null);
        assertEquals(1, messages.size());
        assertEquals("rewritten", messages.get(0).getSource());
    }

    @Test
    void testEvalshaIsTranslatedToEval() throws Exception {
        List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
        RedisCommandCapture capture = capture(messages);
        String script = "return redis.call('get', KEYS[1])";
        String sha = sha1(script);

        CompletableFuture<Object> loadPromise = new CompletableFuture<>();
        capture.capture(scriptLoadCommandData(loadPromise, script));
        loadPromise.complete(null);
        // The SCRIPT LOAD command is not replicated, it only feeds the script registry
        assertTrue(messages.isEmpty());

        CompletableFuture<Object> evalshaPromise = new CompletableFuture<>();
        capture.capture(commandData(evalshaPromise, "EVALSHA", sha, 1, "map:1", "arg"));
        evalshaPromise.complete(null);

        assertEquals(1, messages.size());
        RedisCommandMessage message = messages.get(0);
        assertEquals("EVAL", message.getCommandName());
        assertArrayEquals(script.getBytes(StandardCharsets.UTF_8), message.getArgs()[0]);
        assertEquals("map:1", message.getKey());
    }

    @Test
    void testUnknownEvalshaIsCapturedAsIs() {
        List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
        RedisCommandCapture capture = capture(messages);

        CompletableFuture<Object> evalshaPromise = new CompletableFuture<>();
        capture.capture(commandData(evalshaPromise, "EVALSHA", "unknown-sha", 1, "map:1"));
        evalshaPromise.complete(null);

        assertEquals(1, messages.size());
        assertEquals("EVALSHA", messages.get(0).getCommandName());
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CommandData<?, ?> scriptLoadCommandData(CompletableFuture<Object> promise, String script) {
        return new CommandData(promise, StringCodec.INSTANCE, new RedisCommand<>("SCRIPT", "LOAD"), new Object[]{script});
    }

    private static String sha1(String script) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(script.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
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
