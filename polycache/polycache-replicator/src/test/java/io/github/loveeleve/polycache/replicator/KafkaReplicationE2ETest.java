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
package io.github.loveeleve.polycache.replicator;

import io.github.loveeleve.polycache.redisson.capture.RedisCommandCapture;
import io.github.loveeleve.polycache.redisson.replay.RedisCommandReplayer;
import io.github.loveeleve.polycache.replicator.consumer.KafkaRedisCommandConsumer;
import io.github.loveeleve.polycache.replicator.producer.KafkaRedisCommandProducer;
import io.github.loveeleve.polycache.replicator.serialization.RedisCommandMessageDeserializer;
import io.github.loveeleve.polycache.replicator.serialization.RedisCommandMessageSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the cross-datacenter replication: the writes of the source data center
 * (captured and produced to Kafka) are consumed and replayed against the Redis of the target
 * data center.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class KafkaReplicationE2ETest {

    private static final String TOPIC = "polycache-redis-replication";

    @Test
    void testCrossDatacenterReplication() throws Exception {
        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
        broker.kafkaPorts(0);
        broker.afterPropertiesSet();
        try (RedisServer sourceServer = RedisServer.start();
             RedisServer targetServer = RedisServer.start()) {

            String bootstrapServers = broker.getBrokersAsString();

            // The producer of the source data center
            Map<String, Object> producerProperties = new HashMap<>();
            producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, RedisCommandMessageSerializer.class);
            KafkaRedisCommandProducer producer = new KafkaRedisCommandProducer(
                    new DefaultKafkaProducerFactory<>(producerProperties), TOPIC);

            // The consumer of the target data center
            Map<String, Object> consumerProperties = new HashMap<>();
            consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "polycache-replication-test");
            consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, RedisCommandMessageDeserializer.class);

            RedissonClient targetClient = Redisson.create(config(targetServer));
            KafkaRedisCommandConsumer consumer = new KafkaRedisCommandConsumer(
                    new DefaultKafkaConsumerFactory<>(consumerProperties), TOPIC, "zone-b",
                    new RedisCommandReplayer(targetClient));
            consumer.start();

            // The source data center writes
            RedisCommandCapture capture = RedisCommandCapture.builder()
                    .source("zone-a")
                    .listener(producer)
                    .build();
            Config sourceConfig = config(sourceServer);
            capture.applyTo(sourceConfig);
            RedissonClient sourceClient = Redisson.create(sourceConfig);

            try {
                sourceClient.getBucket("user:1").set("alice");
                sourceClient.getMap("map:1").put("name", "bob");
                sourceClient.getAtomicLong("counter").incrementAndGet();
                // Order-sensitive commands : the consumer must apply them in order
                RList<String> list = sourceClient.getList("list:1");
                list.add("a");
                list.add("b");
                RSet<String> set = sourceClient.getSet("set:1");
                set.add("x");
                set.add("y");
                RMap<String, String> hash = sourceClient.getMap("hash:1");
                hash.fastPut("f1", "v1");
                hash.putAll(Map.of("f2", "v2", "f3", "v3"));
                sourceClient.getBucket("ttl:1").set("value", Duration.ofMinutes(1));
                sourceClient.getBucket("large:1").set("x".repeat(100_000));
                // A batch with the results skipped is captured through the batch promise
                RBatch skipResultBatch = sourceClient.createBatch(BatchOptions.defaults().skipResult());
                skipResultBatch.getBucket("skip:1").setAsync("skip-value");
                skipResultBatch.getSet("skip:2").addAsync("skip-element");
                skipResultBatch.execute();

                awaitUntil(() -> "alice".equals(targetClient.getBucket("user:1").get()));
                assertEquals("bob", targetClient.getMap("map:1").get("name"));
                assertEquals(1L, targetClient.getAtomicLong("counter").get());
                awaitUntil(() -> targetClient.getList("list:1").size() == 2);
                assertEquals(List.of("a", "b"), targetClient.getList("list:1").readAll());
                awaitUntil(() -> targetClient.getSet("set:1").size() == 2);
                assertTrue(targetClient.getSet("set:1").containsAll(Set.of("x", "y")));
                awaitUntil(() -> targetClient.getMap("hash:1").size() == 3);
                assertEquals("v1", targetClient.getMap("hash:1").get("f1"));
                assertEquals("v2", targetClient.getMap("hash:1").get("f2"));
                assertEquals("v3", targetClient.getMap("hash:1").get("f3"));
                awaitUntil(() -> "value".equals(targetClient.getBucket("ttl:1").get()));
                assertTrue(targetClient.getBucket("ttl:1").remainTimeToLive() > 0);
                awaitUntil(() -> targetClient.getBucket("large:1").get() != null);
                assertEquals(100_000, ((String) targetClient.getBucket("large:1").get()).length());
                awaitUntil(() -> "skip-value".equals(targetClient.getBucket("skip:1").get()));
                assertTrue(targetClient.getSet("skip:2").contains("skip-element"));
                assertEquals(0L, producer.getDroppedMessageCount());
            } finally {
                consumer.close();
                producer.close();
                sourceClient.shutdown();
                targetClient.shutdown();
            }
        } finally {
            broker.destroy();
        }
    }

    private static Config config(RedisServer redisServer) {
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
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "The condition was not satisfied in time");
    }
}
