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
package io.github.loveeleve.polycache.redisson;

import io.github.loveeleve.polycache.command.RedisCommandMessage;
import io.github.loveeleve.polycache.redisson.capture.RedisCommandCapture;
import io.github.loveeleve.polycache.redisson.replay.RedisCommandReplayer;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the capture under concurrency : the writes of multiple application threads
 * (multiple connections and Netty event loops) are captured without loss or duplication and are
 * replicated to the target Redis.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ConcurrentCaptureE2ETest {

    private static final int THREAD_COUNT = 4;

    private static final int KEYS_PER_THREAD = 25;

    @Test
    void testConcurrentCapture() throws Exception {
        try (RedisServer sourceServer = RedisServer.start();
             RedisServer targetServer = RedisServer.start()) {

            RedissonClient targetClient = Redisson.create(singleServerConfig(targetServer));
            RedisCommandReplayer replayer = new RedisCommandReplayer(targetClient);

            List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
            RedisCommandCapture capture = RedisCommandCapture.builder()
                    .source("zone-a")
                    .listener(message -> {
                        messages.add(message);
                        replayer.replay(message);
                    })
                    .build();

            Config sourceConfig = singleServerConfig(sourceServer);
            capture.applyTo(sourceConfig);
            RedissonClient sourceClient = Redisson.create(sourceConfig);

            try {
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(THREAD_COUNT);
                for (int thread = 0; thread < THREAD_COUNT; thread++) {
                    int threadIndex = thread;
                    Thread writer = new Thread(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < KEYS_PER_THREAD; i++) {
                                sourceClient.getBucket("thread:" + threadIndex + ":key:" + i)
                                        .set("value-" + threadIndex + "-" + i);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    }, "polycache-writer-" + thread);
                    writer.start();
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "The writers did not finish in time");

                int expectedMessageCount = THREAD_COUNT * KEYS_PER_THREAD;
                awaitUntil(() -> messages.size() == expectedMessageCount);
                for (int thread = 0; thread < THREAD_COUNT; thread++) {
                    for (int i = 0; i < KEYS_PER_THREAD; i++) {
                        int threadIndex = thread;
                        int keyIndex = i;
                        awaitUntil(() -> ("value-" + threadIndex + "-" + keyIndex)
                                .equals(targetClient.getBucket("thread:" + threadIndex + ":key:" + keyIndex).get()));
                    }
                }
                // No loss, no duplication
                Thread.sleep(500);
                assertEquals(expectedMessageCount, messages.size());
            } finally {
                sourceClient.shutdown();
                targetClient.shutdown();
            }
        }
    }

    private static Config singleServerConfig(RedisServer redisServer) {
        Config config = new Config();
        config.useSingleServer().setAddress(redisServer.getAddress());
        return config;
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "The condition was not satisfied in time");
    }
}
