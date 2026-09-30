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
package io.github.loveeleve.polycache.spring.boot.demo;

import io.github.loveeleve.polycache.replicator.producer.KafkaRedisCommandProducer;
import io.github.loveeleve.polycache.spring.boot.autoconfigure.PolyCacheAutoConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBatch;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual demonstration of the two data centers running against the Dockerized Redis containers
 * (for example the {@code redis-lab-7} container on the port 16379 and the {@code redis-lab-8}
 * container on the port 16388).
 *
 * <p>The demonstration runs the full auto-configuration of two applications, each application
 * writing to its own Redis and replicating its writes to the other one through an embedded Kafka
 * broker. Every write command is issued symmetrically in both data centers, so the sent message
 * count of each application must remain equal to its own write count (a replication loop would
 * grow it).
 *
 * <p>If the containers run with the protected mode enabled, disable it for the demonstration
 * without restart :<br>
 * {@code docker exec redis-lab-7 redis-cli config set protected-mode no}<br>
 * {@code docker exec redis-lab-8 redis-cli config set protected-mode no}
 *
 * <p>Run it with : {@code mvn test -pl polycache-spring-boot -am -Dtest=TwoDatacenterDockerDemoTest -Dsurefire.failIfNoSpecifiedTests=false}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class TwoDatacenterDockerDemoTest {

    private static final String TOPIC = "polycache-redis-replication-demo";

    private static final String KEY_PREFIX = "polycache-demo:";

    private static final int REDIS_A_PORT = Integer.getInteger("polycache.demo.redis-a.port", 16379);

    private static final int REDIS_B_PORT = Integer.getInteger("polycache.demo.redis-b.port", 16388);

    /**
     * The number of the write commands issued by each data center.
     */
    private static final long WRITE_COMMAND_COUNT = 6;

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(canConnect(REDIS_A_PORT) && canConnect(REDIS_B_PORT),
                "The Dockerized Redis containers are not reachable on the ports " + REDIS_A_PORT + " / " + REDIS_B_PORT);

        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
        broker.kafkaPorts(0);
        broker.afterPropertiesSet();
        try {
            String bootstrapServers = broker.getBrokersAsString();
            line();
            System.out.println("PolyCache two data centers demo (Docker Redis + Embedded Kafka)");
            line();
            System.out.println("Data center A : Redis @ 127.0.0.1:" + REDIS_A_PORT);
            System.out.println("Data center B : Redis @ 127.0.0.1:" + REDIS_B_PORT);
            System.out.println("Kafka  : " + bootstrapServers + " (topic " + TOPIC + ")");
            line();

            contextRunner("zone-a", REDIS_A_PORT, bootstrapServers).run(contextA ->
                    contextRunner("zone-b", REDIS_B_PORT, bootstrapServers).run(contextB -> {
                        RedissonClient clientA = contextA.getBean(RedissonClient.class);
                        RedissonClient clientB = contextB.getBean(RedissonClient.class);
                        KafkaRedisCommandProducer producerA = contextA.getBean(KafkaRedisCommandProducer.class);
                        KafkaRedisCommandProducer producerB = contextB.getBean(KafkaRedisCommandProducer.class);
                        RedissonClient cleanClientA = cleanupClient(REDIS_A_PORT);
                        RedissonClient cleanClientB = cleanupClient(REDIS_B_PORT);
                        try {
                            clean(cleanClientA);
                            clean(cleanClientB);

                            System.out.println("[1] Each data center issues " + WRITE_COMMAND_COUNT + " write commands (SET / EVAL / INCR / batch / ordered)");
                            write(clientA, "A");
                            write(clientB, "B");

                            System.out.println("[2] Waiting for the cross data center replication ...");
                            awaitUntil(() -> value(clientB, "user:A").equals("alice"));
                            awaitUntil(() -> value(clientA, "user:B").equals("bob"));
                            assertEquals("user-A", mapValue(clientB, "map:A"));
                            assertEquals("user-B", mapValue(clientA, "map:B"));
                            awaitUntil(() -> counter(clientB, "counter:A") == 1L);
                            awaitUntil(() -> counter(clientA, "counter:B") == 1L);
                            awaitUntil(() -> value(clientB, "batch:A").equals("batch-A"));
                            awaitUntil(() -> value(clientA, "batch:B").equals("batch-B"));
                            awaitUntil(() -> list(clientB, "list:A").equals(List.of("1", "2")));
                            awaitUntil(() -> list(clientA, "list:B").equals(List.of("1", "2")));
                            System.out.println("    Data center B received every write of A");
                            System.out.println("    Data center A received every write of B");

                            // A replication loop would keep publishing the replayed commands
                            Thread.sleep(500);
                            assertEquals(WRITE_COMMAND_COUNT, producerA.getSentMessageCount());
                            assertEquals(WRITE_COMMAND_COUNT, producerB.getSentMessageCount());
                            assertEquals(0L, producerA.getDroppedMessageCount());
                            assertEquals(0L, producerB.getDroppedMessageCount());

                            System.out.println("[3] Replication loop check : A sent " + producerA.getSentMessageCount()
                                    + " / B sent " + producerB.getSentMessageCount() + " (equal to the write counts, no loop)");
                            line();
                            dump(clientA, "Data center A (127.0.0.1:" + REDIS_A_PORT + ")", "A");
                            line();
                            dump(clientB, "Data center B (127.0.0.1:" + REDIS_B_PORT + ")", "B");
                            line();
                            System.out.println("DEMO PASSED");
                            line();
                        } finally {
                            clean(cleanClientA);
                            clean(cleanClientB);
                            cleanClientA.shutdown();
                            cleanClientB.shutdown();
                        }
                    }));
        } finally {
            broker.destroy();
        }
    }

    /**
     * Issues the symmetric write commands of one data center.
     */
    private static void write(RedissonClient client, String dataCenter) {
        // 1) SET
        client.getBucket(KEY_PREFIX + "user:" + dataCenter).set("A".equals(dataCenter) ? "alice" : "bob");
        // 2) EVAL (the Redisson map operations are implemented with a Lua script)
        client.getMap(KEY_PREFIX + "map:" + dataCenter).put("name", "user-" + dataCenter);
        // 3) INCR
        client.getAtomicLong(KEY_PREFIX + "counter:" + dataCenter).incrementAndGet();
        // 4) A batch
        RBatch batch = client.createBatch();
        batch.getBucket(KEY_PREFIX + "batch:" + dataCenter).setAsync("batch-" + dataCenter);
        batch.execute();
        // 5) Ordered commands
        client.getList(KEY_PREFIX + "list:" + dataCenter).add("1");
        client.getList(KEY_PREFIX + "list:" + dataCenter).add("2");
    }

    private static void dump(RedissonClient client, String title, String localDataCenter) {
        System.out.println(title + " demo data :");
        for (String dataCenter : List.of("A", "B")) {
            String source = localDataCenter.equals(dataCenter) ? "  <- local write" : "  <- replicated from the other data center";
            System.out.printf("  %suser:%s   = %s%s%n", KEY_PREFIX, dataCenter, value(client, "user:" + dataCenter), source);
            System.out.printf("  %smap:%s    = {name=%s}%n", KEY_PREFIX, dataCenter, mapValue(client, "map:" + dataCenter));
            System.out.printf("  %scounter:%s= %d%n", KEY_PREFIX, dataCenter, counter(client, "counter:" + dataCenter));
            System.out.printf("  %sbatch:%s  = %s%n", KEY_PREFIX, dataCenter, value(client, "batch:" + dataCenter));
            System.out.printf("  %slist:%s   = %s%n", KEY_PREFIX, dataCenter, list(client, "list:" + dataCenter));
        }
    }

    private static String value(RedissonClient client, String key) {
        Object value = client.getBucket(KEY_PREFIX + key).get();
        return value == null ? "<null>" : value.toString();
    }

    private static String mapValue(RedissonClient client, String key) {
        Object value = client.getMap(KEY_PREFIX + key).get("name");
        return value == null ? "<null>" : value.toString();
    }

    private static long counter(RedissonClient client, String key) {
        return client.getAtomicLong(KEY_PREFIX + key).get();
    }

    private static List<String> list(RedissonClient client, String key) {
        return client.<String>getList(KEY_PREFIX + key).readAll();
    }

    /**
     * The cleanup and the dumps must not go through the captured application clients, otherwise the
     * DEL commands of the cleanup would be replicated and would distort the message counters.
     */
    private static RedissonClient cleanupClient(int port) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port);
        return Redisson.create(config);
    }

    private static void clean(RedissonClient client) {
        long deleted = client.getKeys().deleteByPattern(KEY_PREFIX + "*");
        if (deleted > 0) {
            System.out.println("    Cleaned up " + deleted + " demo keys");
        }
    }

    private static ApplicationContextRunner contextRunner(String source, int redisPort, String bootstrapServers) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class,
                        RedissonAutoConfigurationV2.class, PolyCacheAutoConfiguration.class))
                .withPropertyValues(
                        "polycache.replication.enabled=true",
                        "polycache.replication.source=" + source,
                        "polycache.replication.topic=" + TOPIC,
                        "polycache.replication.consumer-group=" + source + "-demo",
                        "spring.kafka.bootstrap-servers=" + bootstrapServers,
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=" + redisPort);
    }

    /**
     * The containers running with the protected mode enabled accept the connections but reject the
     * commands, so a protocol level ping is required to decide whether the demo can run.
     */
    private static boolean canConnect(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            socket.setSoTimeout(500);
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] response = new byte[64];
            int read = socket.getInputStream().read(response);
            return read > 0 && new String(response, 0, read, StandardCharsets.US_ASCII).startsWith("+PONG");
        } catch (IOException e) {
            return false;
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

    private static void line() {
        System.out.println("------------------------------------------------------------------------");
    }
}
