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
import io.github.loveeleve.polycache.replicator.serialization.RedisCommandMessageDeserializer;
import io.github.loveeleve.polycache.replicator.serialization.RedisCommandMessageSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.Assert;

import java.util.Map;

/**
 * The auto-configuration of PolyCache. It wires the capture, the Kafka producer, the Kafka
 * consumer and the replayer when {@code polycache.replication.enabled} is set to {@code true}.
 *
 * <p>When the Redisson Spring Boot starter is present, the capture is applied to the
 * auto-configured Redisson {@link org.redisson.config.Config} through a
 * {@code RedissonAutoConfigurationCustomizer}, so the application code is not required to change.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@AutoConfiguration(afterName = {
        "org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "org.redisson.spring.starter.RedissonAutoConfiguration",
        "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"})
@ConditionalOnClass({RedissonClient.class, KafkaTemplate.class})
@ConditionalOnProperty(prefix = PolyCacheProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PolyCacheProperties.class)
public class PolyCacheAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(PolyCacheAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(RedissonClient.class)
    public RedisCommandReplayer redisCommandReplayer(RedissonClient applicationClient,
                                                     ObjectProvider<PolyCacheRedissonConfigHolder> configHolderProvider) {
        PolyCacheRedissonConfigHolder configHolder = configHolderProvider.getIfAvailable();
        Config replayConfig = configHolder == null ? null : configHolder.getReplayConfig();
        if (replayConfig == null) {
            // Without the Redisson Spring Boot starter, no Config can be captured : the application
            // client is used, it must not have the capture installed, otherwise the replication loops
            logger.warn("No Redisson configuration was captured, the commands are replayed through the application "
                    + "RedissonClient; make sure that it does not capture the commands, otherwise the replication would loop");
            return new RedisCommandReplayer(applicationClient);
        }
        // The dedicated replay client does not capture the commands, it is shut down with the replayer
        return new RedisCommandReplayer(Redisson.create(replayConfig), true);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaRedisCommandProducer kafkaRedisCommandProducer(KafkaProperties kafkaProperties, ObjectProvider<SslBundles> sslBundles,
                                                               PolyCacheProperties properties) {
        String topic = properties.getTopic();
        Assert.hasText(topic, "The property [" + PolyCacheProperties.PREFIX + ".topic] must not be empty");
        Map<String, Object> configs = kafkaProperties.buildProducerProperties(sslBundles.getIfAvailable());
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, RedisCommandMessageSerializer.class);
        return new KafkaRedisCommandProducer(new DefaultKafkaProducerFactory<>(configs), topic);
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisCommandCapture redisCommandCapture(KafkaRedisCommandProducer producer, PolyCacheProperties properties,
                                                   ObjectProvider<RedisCommandInterceptor> interceptors) {
        String source = properties.getSource();
        Assert.hasText(source, "The property [" + PolyCacheProperties.PREFIX + ".source] must be set when the replication is enabled");
        return RedisCommandCapture.builder()
                .source(source)
                .listener(producer)
                // The RedisCommandInterceptor beans are collected and ordered by Spring
                .interceptors(interceptors.orderedStream().toList())
                .build();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(RedisCommandReplayer.class)
    @ConditionalOnProperty(prefix = PolyCacheProperties.PREFIX + ".consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
    public KafkaRedisCommandConsumer kafkaRedisCommandConsumer(KafkaProperties kafkaProperties, ObjectProvider<SslBundles> sslBundles,
                                                               RedisCommandReplayer replayer, PolyCacheProperties properties) {
        String consumerGroup = properties.getConsumerGroup();
        Assert.hasText(consumerGroup, "The property [" + PolyCacheProperties.PREFIX + ".consumer-group] must not be empty");
        Map<String, Object> configs = kafkaProperties.buildConsumerProperties(sslBundles.getIfAvailable());
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, RedisCommandMessageDeserializer.class);
        configs.putIfAbsent(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        // Without an initial offset, the consumer must read the commands produced before it started,
        // otherwise the commands produced during the startup/rebalance window are silently lost
        configs.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaRedisCommandConsumer(new DefaultKafkaConsumerFactory<>(configs), properties.getTopic(),
                properties.getSource(), replayer);
    }

    /**
     * Applies the capture to the Redisson {@code Config} auto-configured by the Redisson Spring
     * Boot starter, and captures the configuration of the dedicated replay client.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.redisson.spring.starter.RedissonAutoConfigurationCustomizer")
    static class RedissonAutoConfigurationCustomizerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        PolyCacheRedissonConfigHolder polyCacheRedissonConfigHolder(RedisCommandCapture capture) {
            return new PolyCacheRedissonConfigHolder(capture);
        }

        @Bean
        @ConditionalOnMissingBean
        public org.redisson.spring.starter.RedissonAutoConfigurationCustomizer polyCacheRedissonAutoConfigurationCustomizer(PolyCacheRedissonConfigHolder configHolder) {
            return configHolder;
        }
    }
}
