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
import io.github.loveeleve.polycache.redisson.RedisServer;
import io.github.loveeleve.polycache.redisson.replay.RedisCommandReplayer;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBatch;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the capture and replay: the write commands of a source Redis are captured and
 * replayed against a target Redis, acting as two data centers connected back-to-back.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class RedisCommandCaptureE2ETest {

    /**
     * With the script cache enabled, Redisson sends {@code SCRIPT LOAD} and {@code EVALSHA}: the
     * capture must replicate the commands as {@code EVAL} with the script body, so the replay does
     * not depend on the script cache of the target Redis.
     */
    @Test
    void testCaptureAndReplayWithScriptCache() throws Exception {
        try (RedisServer sourceServer = RedisServer.start();
             RedisServer targetServer = RedisServer.start()) {

            RedissonClient targetClient = Redisson.create(config(targetServer));
            RedisCommandReplayer replayer = new RedisCommandReplayer(targetClient);

            List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
            RedisCommandCapture capture = RedisCommandCapture.builder()
                    .source("zone-a")
                    .listener(message -> {
                        messages.add(message);
                        replayer.replay(message);
                    })
                    .build();

            Config sourceConfig = config(sourceServer);
            sourceConfig.setUseScriptCache(true);
            capture.applyTo(sourceConfig);
            RedissonClient sourceClient = Redisson.create(sourceConfig);
            try {
                RMap<String, String> map = sourceClient.getMap("map:1");
                map.put("name", "bob");
                map.put("city", "paris");

                awaitUntil(() -> "bob".equals(targetClient.getMap("map:1").get("name")));
                assertEquals("paris", targetClient.getMap("map:1").get("city"));

                // The EVALSHA commands were translated into EVAL, the SCRIPT LOAD was not replicated
                assertTrue(messages.stream().anyMatch(message -> "EVAL".equals(message.getCommandName())));
                assertTrue(messages.stream().noneMatch(message -> "EVALSHA".equals(message.getCommandName())));
                assertTrue(messages.stream().noneMatch(message -> "SCRIPT".equals(message.getCommandName())));
            } finally {
                sourceClient.shutdown();
                targetClient.shutdown();
            }
        }
    }

    @Test
    void testCaptureAndReplay() throws Exception {
        try (RedisServer sourceServer = RedisServer.start();
             RedisServer targetServer = RedisServer.start()) {

            RedissonClient targetClient = Redisson.create(config(targetServer));
            RedisCommandReplayer replayer = new RedisCommandReplayer(targetClient);

            List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
            RedisCommandCapture capture = RedisCommandCapture.builder()
                    .source("zone-a")
                    .listener(message -> {
                        messages.add(message);
                        replayer.replay(message);
                    })
                    .build();

            Config sourceConfig = config(sourceServer);
            capture.applyTo(sourceConfig);
            RedissonClient sourceClient = Redisson.create(sourceConfig);

            try {
                RBucket<String> bucket = sourceClient.getBucket("user:1");
                bucket.set("alice");

                RMap<String, String> map = sourceClient.getMap("map:1");
                map.put("name", "bob");

                RAtomicLong counter = sourceClient.getAtomicLong("counter");
                counter.incrementAndGet();

                RBatch batch = sourceClient.createBatch();
                batch.getBucket("batch:1").setAsync("batch-value");
                batch.execute();

                // Reads are not captured
                assertEquals("alice", bucket.get());
                assertTrue(messages.stream().noneMatch(message -> "GET".equals(message.getCommandName())));

                awaitUntil(() -> "alice".equals(targetClient.getBucket("user:1").get()));
                assertEquals("bob", targetClient.getMap("map:1").get("name"));
                assertEquals(1L, targetClient.getAtomicLong("counter").get());
                awaitUntil(() -> "batch-value".equals(targetClient.getBucket("batch:1").get()));

                // The captured commands carry the source and the routing key
                assertFalse(messages.isEmpty());
                assertTrue(messages.stream().allMatch(message -> "zone-a".equals(message.getSource())));
                assertTrue(messages.stream().anyMatch(message -> "SET".equals(message.getCommandName())
                        && "user:1".equals(message.getKey())));
                // The commands of a batch are captured with their own routing key
                assertTrue(messages.stream().anyMatch(message -> "SET".equals(message.getCommandName())
                        && "batch:1".equals(message.getKey())));
            } finally {
                sourceClient.shutdown();
                targetClient.shutdown();
            }
        }
    }

    private static Config config(RedisServer redisServer) {
        Config config = new Config();
        config.useSingleServer().setAddress(redisServer.getAddress());
        return config;
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "The condition was not satisfied in time");
    }
}
