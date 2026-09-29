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

import io.github.loveeleve.polycache.redisson.capture.RedisCommandCapture;
import io.github.loveeleve.polycache.redisson.replay.RedisCommandReplayer;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the replay into a Redis Cluster : the commands captured from a standalone
 * Redis are replayed through a cluster client, so every command is routed to the node owning the
 * key of the command.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ClusterReplicationE2ETest {

    @Test
    void testReplayIntoCluster() throws Exception {
        try (RedisCluster cluster = RedisCluster.start(3);
             RedisServer sourceServer = RedisServer.start()) {

            Config targetConfig = new Config();
            targetConfig.useClusterServers().addNodeAddress(cluster.getNodeAddresses().toArray(new String[0]));
            RedissonClient targetClient = Redisson.create(targetConfig);

            RedisCommandReplayer replayer = new RedisCommandReplayer(targetClient);
            RedisCommandCapture capture = RedisCommandCapture.builder()
                    .source("zone-a")
                    .listener(replayer::replay)
                    .build();

            Config sourceConfig = singleServerConfig(sourceServer);
            capture.applyTo(sourceConfig);
            RedissonClient sourceClient = Redisson.create(sourceConfig);

            try {
                sourceClient.getBucket("user:1").set("alice");
                sourceClient.getMap("map:1").put("name", "bob");
                sourceClient.getSet("set:1").add("x");
                sourceClient.getList("list:1").add("a");
                // The keys are spread over the slots of the cluster
                for (int i = 0; i < 20; i++) {
                    sourceClient.getBucket("spread:" + i).set("value-" + i);
                }

                awaitUntil(() -> "alice".equals(targetClient.getBucket("user:1").get()));
                assertEquals("bob", targetClient.getMap("map:1").get("name"));
                assertTrue(targetClient.getSet("set:1").contains("x"));
                assertEquals(List.of("a"), targetClient.getList("list:1").readAll());
                for (int i = 0; i < 20; i++) {
                    int index = i;
                    awaitUntil(() -> ("value-" + index).equals(targetClient.getBucket("spread:" + index).get()));
                }
            } finally {
                sourceClient.shutdown();
                targetClient.shutdown();
            }
        }
    }

    /**
     * The capture works on a cluster client : the commands of the multiple node connections are
     * captured, including the commands redirected (MOVED) to another node.
     */
    @Test
    void testCaptureFromCluster() throws Exception {
        try (RedisCluster cluster = RedisCluster.start(3);
             RedisServer targetServer = RedisServer.start()) {

            RedissonClient targetClient = Redisson.create(singleServerConfig(targetServer));
            RedisCommandReplayer replayer = new RedisCommandReplayer(targetClient);

            RedisCommandCapture capture = RedisCommandCapture.builder()
                    .source("zone-a")
                    .listener(replayer::replay)
                    .build();

            Config sourceConfig = new Config();
            sourceConfig.useClusterServers().addNodeAddress(cluster.getNodeAddresses().toArray(new String[0]));
            capture.applyTo(sourceConfig);
            RedissonClient sourceClient = Redisson.create(sourceConfig);

            try {
                for (int i = 0; i < 20; i++) {
                    sourceClient.getBucket("cluster:" + i).set("value-" + i);
                }
                for (int i = 0; i < 20; i++) {
                    int index = i;
                    awaitUntil(() -> ("value-" + index).equals(targetClient.getBucket("cluster:" + index).get()));
                }
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
