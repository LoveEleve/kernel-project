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
package io.github.loveeleve.polycache.redisson.routing;

import io.github.loveeleve.polycache.command.RedisCommandMessage;
import io.github.loveeleve.polycache.redisson.RedisServer;
import io.github.loveeleve.polycache.redisson.capture.RedisCommandCapture;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of {@link DynamicRedissonClient} : one routing client dispatches its operations
 * to one of the registered Redis servers, selected per thread, and a target can be replaced at
 * runtime.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class DynamicRedissonClientE2ETest {

    @Test
    void testDynamicRouting() throws Exception {
        try (RedisServer primary = RedisServer.start();
             RedisServer secondary = RedisServer.start();
             RedisServer replacement = RedisServer.start()) {

            DynamicRedissonClient dynamic = DynamicRedissonClient.create(singleServerConfig(primary));
            dynamic.addTarget("secondary", singleServerConfig(secondary));
            RedissonClient client = dynamic.getClient();

            RedissonClient primaryClient = Redisson.create(singleServerConfig(primary));
            RedissonClient secondaryClient = Redisson.create(singleServerConfig(secondary));
            RedissonClient replacementClient = Redisson.create(singleServerConfig(replacement));
            try {
                // [1] No target selected : the default target is used
                client.getBucket("key:default").set("value-default");
                assertEquals("value-default", primaryClient.getBucket("key:default").get());
                assertNull(secondaryClient.getBucket("key:default").get());

                // [2] The target is selected on the current thread only
                dynamic.switchTarget("secondary");
                try {
                    assertEquals("secondary", dynamic.getCurrentTarget());
                    assertEquals(dynamic.getTargetNames().contains("secondary"), true);
                    client.getBucket("key:secondary").set("value-secondary");
                } finally {
                    dynamic.clearTarget();
                }
                assertEquals("value-secondary", secondaryClient.getBucket("key:secondary").get());
                assertNull(primaryClient.getBucket("key:secondary").get());
                assertNull(dynamic.getCurrentTarget());

                // [2b] The data structures acquired while a target is selected stay bound to it
                dynamic.switchTarget("secondary");
                RBucket<String> secondaryBucket = client.getBucket("key:bound");
                dynamic.clearTarget();
                secondaryBucket.set("value-bound");
                assertEquals("value-bound", secondaryClient.getBucket("key:bound").get());
                assertNull(primaryClient.getBucket("key:bound").get());

                // [3] Multiple threads route to their own targets concurrently
                CountDownLatch done = new CountDownLatch(2);
                Thread defaultThread = new Thread(() -> {
                    try {
                        for (int i = 0; i < 10; i++) {
                            client.getBucket("key:thread:default:" + i).set("default-" + i);
                        }
                    } finally {
                        done.countDown();
                    }
                });
                Thread secondaryThread = new Thread(() -> {
                    try {
                        dynamic.switchTarget("secondary");
                        for (int i = 0; i < 10; i++) {
                            client.getBucket("key:thread:secondary:" + i).set("secondary-" + i);
                        }
                    } finally {
                        dynamic.clearTarget();
                        done.countDown();
                    }
                });
                defaultThread.start();
                secondaryThread.start();
                assertTrue(done.await(30, TimeUnit.SECONDS), "The routing threads did not finish in time");
                for (int i = 0; i < 10; i++) {
                    assertEquals("default-" + i, primaryClient.getBucket("key:thread:default:" + i).get());
                    assertNull(secondaryClient.getBucket("key:thread:default:" + i).get());
                    assertEquals("secondary-" + i, secondaryClient.getBucket("key:thread:secondary:" + i).get());
                    assertNull(primaryClient.getBucket("key:thread:secondary:" + i).get());
                }

                // [4] The default target is replaced at runtime (a configuration change)
                dynamic.replaceTarget(DynamicRedissonClient.DEFAULT_TARGET, singleServerConfig(replacement));
                client.getBucket("key:replaced").set("value-replaced");
                assertEquals("value-replaced", replacementClient.getBucket("key:replaced").get());
                assertNull(primaryClient.getBucket("key:replaced").get());

                // [5] A target can be removed
                dynamic.removeTarget("secondary");
                assertFalse(dynamic.getTargetNames().contains("secondary"));
                assertEquals(1, dynamic.getTargetNames().size());
            } finally {
                dynamic.shutdown();
                primaryClient.shutdown();
                secondaryClient.shutdown();
                replacementClient.shutdown();
            }
        }
    }

    /**
     * The routing and the capture work together : every target client captures its own writes, so
     * the writes of the application are replicated whether they go to the default target or to a
     * switched one.
     */
    @Test
    void testRoutingWithCapture() throws Exception {
        try (RedisServer primary = RedisServer.start();
             RedisServer secondary = RedisServer.start()) {

            List<RedisCommandMessage> messages = new CopyOnWriteArrayList<>();
            RedisCommandCapture capture = RedisCommandCapture.builder()
                    .source("zone-a")
                    .listener(messages::add)
                    .build();

            Config primaryConfig = singleServerConfig(primary);
            capture.applyTo(primaryConfig);
            Config secondaryConfig = singleServerConfig(secondary);
            capture.applyTo(secondaryConfig);

            DynamicRedissonClient dynamic = DynamicRedissonClient.create(primaryConfig);
            dynamic.addTarget("secondary", secondaryConfig);
            RedissonClient client = dynamic.getClient();

            RedissonClient primaryClient = Redisson.create(singleServerConfig(primary));
            RedissonClient secondaryClient = Redisson.create(singleServerConfig(secondary));
            try {
                client.getBucket("route:default").set("value-default");
                dynamic.runWithTarget("secondary",
                        () -> client.getBucket("route:secondary").set("value-secondary"));

                awaitUntil(() -> messages.size() == 2);
                assertTrue(messages.stream().anyMatch(message -> "SET".equals(message.getCommandName())
                        && "route:default".equals(message.getKey())));
                assertTrue(messages.stream().anyMatch(message -> "SET".equals(message.getCommandName())
                        && "route:secondary".equals(message.getKey())));
                assertTrue(messages.stream().allMatch(message -> "zone-a".equals(message.getSource())));

                assertEquals("value-default", primaryClient.getBucket("route:default").get());
                assertNull(primaryClient.getBucket("route:secondary").get());
                assertEquals("value-secondary", secondaryClient.getBucket("route:secondary").get());
                assertNull(secondaryClient.getBucket("route:default").get());
            } finally {
                dynamic.shutdown();
                primaryClient.shutdown();
                secondaryClient.shutdown();
            }
        }
    }

    /**
     * When the selected target was removed (for example by another thread), the calls fall back to
     * the default target and the stale thread selection is cleared.
     */
    @Test
    void testRemovedSelectedTargetFallsBackToDefault() throws Exception {
        try (RedisServer primary = RedisServer.start();
             RedisServer secondary = RedisServer.start()) {

            DynamicRedissonClient dynamic = DynamicRedissonClient.create(singleServerConfig(primary));
            dynamic.addTarget("secondary", singleServerConfig(secondary));
            RedissonClient client = dynamic.getClient();
            RedissonClient primaryClient = Redisson.create(singleServerConfig(primary));
            try {
                dynamic.switchTarget("secondary");
                dynamic.removeTarget("secondary");

                client.getBucket("fallback:key").set("value");
                assertEquals("value", primaryClient.getBucket("fallback:key").get());
                assertNull(dynamic.getCurrentTarget());
            } finally {
                dynamic.shutdown();
                primaryClient.shutdown();
            }
        }
    }

    @Test
    void testScopedSwitching() throws Exception {
        try (RedisServer primary = RedisServer.start();
             RedisServer secondary = RedisServer.start()) {

            DynamicRedissonClient dynamic = DynamicRedissonClient.create(singleServerConfig(primary));
            dynamic.addTarget("secondary", singleServerConfig(secondary));
            RedissonClient client = dynamic.getClient();

            RedissonClient primaryClient = Redisson.create(singleServerConfig(primary));
            RedissonClient secondaryClient = Redisson.create(singleServerConfig(secondary));
            try {
                // The scoped call routes to the target and restores the selection afterwards
                String result = dynamic.callWithTarget("secondary", () -> {
                    client.getBucket("scoped:call").set("call-value");
                    return "done";
                });
                assertEquals("done", result);
                assertNull(dynamic.getCurrentTarget());
                assertEquals("call-value", secondaryClient.getBucket("scoped:call").get());
                assertNull(primaryClient.getBucket("scoped:call").get());

                // The scopes are nestable and the previous selection is restored
                dynamic.runWithTarget("secondary", () -> {
                    client.getBucket("scoped:outer").set("outer-value");
                    dynamic.runWithTarget(DynamicRedissonClient.DEFAULT_TARGET,
                            () -> client.getBucket("scoped:inner").set("inner-value"));
                    assertEquals("secondary", dynamic.getCurrentTarget());
                    client.getBucket("scoped:after-inner").set("after-value");
                });
                assertEquals("outer-value", secondaryClient.getBucket("scoped:outer").get());
                assertEquals("after-value", secondaryClient.getBucket("scoped:after-inner").get());
                assertEquals("inner-value", primaryClient.getBucket("scoped:inner").get());
                assertNull(secondaryClient.getBucket("scoped:inner").get());
                assertNull(dynamic.getCurrentTarget());
            } finally {
                dynamic.shutdown();
                primaryClient.shutdown();
                secondaryClient.shutdown();
            }
        }
    }

    @Test
    void testDefaultTargetIsSwitchable() throws Exception {
        try (RedisServer primary = RedisServer.start();
             RedisServer secondary = RedisServer.start()) {

            DynamicRedissonClient dynamic = DynamicRedissonClient.create(singleServerConfig(primary));
            dynamic.addTarget("secondary", singleServerConfig(secondary));
            RedissonClient client = dynamic.getClient();

            RedissonClient primaryClient = Redisson.create(singleServerConfig(primary));
            RedissonClient secondaryClient = Redisson.create(singleServerConfig(secondary));
            try {
                dynamic.setDefaultTarget("secondary");
                assertEquals("secondary", dynamic.getDefaultTarget());
                client.getBucket("key:new-default").set("value");
                assertEquals("value", secondaryClient.getBucket("key:new-default").get());
                assertNull(primaryClient.getBucket("key:new-default").get());

                dynamic.setDefaultTarget(DynamicRedissonClient.DEFAULT_TARGET);
                client.getBucket("key:restored-default").set("value");
                assertEquals("value", primaryClient.getBucket("key:restored-default").get());
                assertNull(secondaryClient.getBucket("key:restored-default").get());
            } finally {
                dynamic.shutdown();
                primaryClient.shutdown();
                secondaryClient.shutdown();
            }
        }
    }

    @Test
    void testValidationAndShutdown() throws Exception {
        try (RedisServer primary = RedisServer.start();
             RedisServer secondary = RedisServer.start()) {

            DynamicRedissonClient dynamic = DynamicRedissonClient.create(singleServerConfig(primary));
            dynamic.addTarget("secondary", singleServerConfig(secondary));

            assertThrows(IllegalArgumentException.class, () -> dynamic.switchTarget("unknown"));
            assertThrows(NullPointerException.class, () -> dynamic.addTarget(null, singleServerConfig(secondary)));
            assertThrows(NullPointerException.class, () -> dynamic.addTarget("target", (RedissonClient) null));
            assertThrows(IllegalArgumentException.class, () -> dynamic.replaceTarget("unknown", singleServerConfig(secondary)));
            assertThrows(IllegalArgumentException.class, () -> dynamic.removeTarget(DynamicRedissonClient.DEFAULT_TARGET));

            // The shutdown overload of the client shuts down all the targets
            RedissonClient client = dynamic.getClient();
            client.shutdown(1, 1, TimeUnit.SECONDS);
            assertTrue(dynamic.isShutdown());
            assertTrue(client.isShutdown());
            assertThrows(IllegalStateException.class, () -> client.getBucket("key:after-shutdown"));
            assertThrows(IllegalStateException.class, () -> dynamic.switchTarget("secondary"));
            assertThrows(IllegalStateException.class, () -> dynamic.addTarget("late", singleServerConfig(secondary)));
            assertThrows(IllegalStateException.class, () -> dynamic.replaceTarget("secondary", singleServerConfig(secondary)));
        }
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

    private static Config singleServerConfig(RedisServer redisServer) {
        Config config = new Config();
        config.useSingleServer().setAddress(redisServer.getAddress());
        return config;
    }
}
