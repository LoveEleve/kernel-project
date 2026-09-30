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

import io.github.loveeleve.polycache.redisson.RedisServer;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static Config singleServerConfig(RedisServer redisServer) {
        Config config = new Config();
        config.useSingleServer().setAddress(redisServer.getAddress());
        return config;
    }
}
