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
package io.github.loveeleve.polycache.spring.boot.autoconfigure;

import io.github.loveeleve.polycache.replicator.producer.KafkaRedisCommandProducer;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the two data centers with the full auto-configuration : two applications with
 * their own Redis and their own Redisson clients replicate the writes to each other through one
 * Kafka topic, and the replayed commands are not published again (no replication loop).
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class PolyCacheSpringBootE2ETest {

    private static final String TOPIC = "polycache-redis-replication-e2e";

    @Test
    void testCrossDatacenterReplicationWithTheAutoConfiguration() throws Exception {
        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
        broker.kafkaPorts(0);
        broker.afterPropertiesSet();
        try (RedisServer redisServerA = RedisServer.start();
             RedisServer redisServerB = RedisServer.start()) {

            String bootstrapServers = broker.getBrokersAsString();

            contextRunner("zone-a", bootstrapServers, redisServerA.getPort()).run(contextA ->
                    contextRunner("zone-b", bootstrapServers, redisServerB.getPort()).run(contextB -> {
                        RedissonClient clientA = contextA.getBean(RedissonClient.class);
                        RedissonClient clientB = contextB.getBean(RedissonClient.class);

                        // Both data centers write their own data
                        clientA.getBucket("user:1").set("alice");
                        clientB.getBucket("user:2").set("bob");

                        // The writes are replicated to the other data center
                        awaitUntil(() -> "alice".equals(clientB.getBucket("user:1").get()));
                        awaitUntil(() -> "bob".equals(clientA.getBucket("user:2").get()));

                        // A replication loop would keep publishing the replayed commands
                        Thread.sleep(500);
                        KafkaRedisCommandProducer producerA = contextA.getBean(KafkaRedisCommandProducer.class);
                        KafkaRedisCommandProducer producerB = contextB.getBean(KafkaRedisCommandProducer.class);
                        assertEquals(1L, producerA.getSentMessageCount());
                        assertEquals(1L, producerB.getSentMessageCount());
                        assertEquals(0L, producerA.getDroppedMessageCount());
                        assertEquals(0L, producerB.getDroppedMessageCount());
                    }));
        } finally {
            broker.destroy();
        }
    }

    private static ApplicationContextRunner contextRunner(String source, String bootstrapServers, int redisPort) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class,
                        RedissonAutoConfigurationV2.class, PolyCacheAutoConfiguration.class))
                .withPropertyValues(
                        "polycache.replication.enabled=true",
                        "polycache.replication.source=" + source,
                        "polycache.replication.topic=" + TOPIC,
                        "polycache.replication.consumer-group=" + source + "-replication",
                        "spring.kafka.bootstrap-servers=" + bootstrapServers,
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=" + redisPort);
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "The condition was not satisfied in time");
    }
}
