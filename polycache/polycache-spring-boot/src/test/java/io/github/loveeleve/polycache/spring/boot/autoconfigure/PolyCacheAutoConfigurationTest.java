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

import io.github.loveeleve.polycache.redisson.capture.RedisCommandCapture;
import io.github.loveeleve.polycache.redisson.capture.RedisCommandInterceptor;
import io.github.loveeleve.polycache.redisson.replay.RedisCommandReplayer;
import io.github.loveeleve.polycache.replicator.consumer.KafkaRedisCommandConsumer;
import io.github.loveeleve.polycache.replicator.producer.KafkaRedisCommandProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PolyCacheAutoConfiguration} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class PolyCacheAutoConfigurationTest {

    private static RedisServer redisServer;

    private static RedissonClient redissonClient;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, PolyCacheAutoConfiguration.class));

    @BeforeAll
    static void beforeAll() throws Exception {
        redisServer = RedisServer.start();
        Config config = new Config();
        config.useSingleServer().setAddress(redisServer.getAddress());
        redissonClient = Redisson.create(config);
    }

    @AfterAll
    static void afterAll() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (redisServer != null) {
            redisServer.close();
        }
    }

    @Test
    void testDisabledByDefault() {
        contextRunner.withBean(RedissonClient.class, () -> redissonClient)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RedisCommandCapture.class);
                    assertThat(context).doesNotHaveBean(KafkaRedisCommandProducer.class);
                    assertThat(context).doesNotHaveBean(KafkaRedisCommandConsumer.class);
                });
    }

    @Test
    void testAutoConfiguration() {
        contextRunner.withPropertyValues(
                        "polycache.replication.enabled=true",
                        "polycache.replication.source=zone-a",
                        "polycache.replication.consumer.enabled=false",
                        "spring.kafka.bootstrap-servers=localhost:9092")
                .withBean(RedissonClient.class, () -> redissonClient)
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisCommandCapture.class);
                    assertThat(context).hasSingleBean(KafkaRedisCommandProducer.class);
                    assertThat(context).hasSingleBean(RedisCommandReplayer.class);
                    assertThat(context).doesNotHaveBean(KafkaRedisCommandConsumer.class);

                    RedisCommandCapture capture = context.getBean(RedisCommandCapture.class);
                    assertThat(capture.getSource()).isEqualTo("zone-a");
                    assertThat(capture.getListener()).isInstanceOf(KafkaRedisCommandProducer.class);
                });
    }

    @Test
    void testConsumerAutoConfiguration() {
        contextRunner.withPropertyValues(
                        "polycache.replication.enabled=true",
                        "polycache.replication.source=zone-a",
                        "spring.kafka.bootstrap-servers=localhost:9092")
                .withBean(RedissonClient.class, () -> redissonClient)
                .run(context -> assertThat(context).hasSingleBean(KafkaRedisCommandConsumer.class));
    }

    @Test
    void testInterceptorBeansAreWiredIntoTheCapture() {
        contextRunner.withPropertyValues(
                        "polycache.replication.enabled=true",
                        "polycache.replication.source=zone-a",
                        "polycache.replication.consumer.enabled=false",
                        "spring.kafka.bootstrap-servers=localhost:9092")
                .withBean(RedissonClient.class, () -> redissonClient)
                .withBean("temporaryKeyFilter", RedisCommandInterceptor.class, () -> message -> null)
                .run(context -> assertThat(context.getBean(RedisCommandCapture.class).getInterceptors()).hasSize(1));
    }

    @Test
    void testReplayClientIsDedicatedWithTheRedissonStarter() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class,
                        RedissonAutoConfigurationV2.class, PolyCacheAutoConfiguration.class))
                .withPropertyValues(
                        "polycache.replication.enabled=true",
                        "polycache.replication.source=zone-a",
                        "polycache.replication.consumer.enabled=false",
                        "spring.kafka.bootstrap-servers=localhost:9092",
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=" + redisServer.getPort())
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisCommandCapture.class);
                    RedissonClient applicationClient = context.getBean(RedissonClient.class);
                    RedisCommandReplayer replayer = context.getBean(RedisCommandReplayer.class);
                    // The replay client is a dedicated client, not the captured application client
                    assertThat(replayer.getRedissonClient()).isNotSameAs(applicationClient);
                    assertThat(replayer.getRedissonClient().isShutdown()).isFalse();
                    // The configuration of the replay client does not capture the commands
                    PolyCacheRedissonConfigHolder configHolder = context.getBean(PolyCacheRedissonConfigHolder.class);
                    Config customizedConfig = new Config();
                    configHolder.customize(customizedConfig);
                    assertThat(customizedConfig.getNettyHook()).isNotNull();
                    assertThat(configHolder.getReplayConfig().getNettyHook()).isNotSameAs(customizedConfig.getNettyHook());
                });
    }

    @Test
    void testBlankTopicFailsFast() {
        contextRunner.withPropertyValues(
                        "polycache.replication.enabled=true",
                        "polycache.replication.source=zone-a",
                        "polycache.replication.consumer.enabled=false",
                        "polycache.replication.topic=",
                        "spring.kafka.bootstrap-servers=localhost:9092")
                .withBean(RedissonClient.class, () -> redissonClient)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void testCaptureAppliedToTheRedissonConfig() {
        contextRunner.withPropertyValues(
                        "polycache.replication.enabled=true",
                        "polycache.replication.source=zone-a",
                        "polycache.replication.consumer.enabled=false",
                        "spring.kafka.bootstrap-servers=localhost:9092")
                .withBean(RedissonClient.class, () -> redissonClient)
                .run(context -> {
                    RedissonAutoConfigurationCustomizer customizer = context.getBean(RedissonAutoConfigurationCustomizer.class);
                    Config config = new Config();
                    customizer.customize(config);
                    assertThat(config.getNettyHook()).isNotNull();
                });
    }
}
