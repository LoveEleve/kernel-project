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
package io.github.loveeleve.polycache.replicator.consumer;

import io.github.loveeleve.polycache.command.RedisCommandMessage;
import io.github.loveeleve.polycache.redisson.replay.RedisCommandReplayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * The Kafka consumer that replicates the commands produced by the other data centers into the
 * local Redis through the {@link RedisCommandReplayer}.
 *
 * <p>The messages produced by the local source (the same {@code source} as this consumer) are
 * skipped, so a data center never re-applies its own writes.
 *
 * <p>The consumer factory should configure {@code auto.offset.reset=earliest}, otherwise the
 * commands produced before this consumer started (or during a rebalance) are silently lost.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   ConsumerFactory<String, RedisCommandMessage> consumerFactory = new DefaultKafkaConsumerFactory<>(configs);
 *   RedisCommandReplayer replayer = new RedisCommandReplayer(localRedissonClient);
 *
 *   KafkaRedisCommandConsumer consumer = new KafkaRedisCommandConsumer(consumerFactory,
 *           "redis-replication", "zone-a", replayer);
 *   consumer.start();
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class KafkaRedisCommandConsumer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(KafkaRedisCommandConsumer.class);

    private final KafkaMessageListenerContainer<String, RedisCommandMessage> container;

    private final String localSource;

    private final RedisCommandReplayer replayer;

    public KafkaRedisCommandConsumer(ConsumerFactory<String, RedisCommandMessage> consumerFactory, String topic,
                                     String localSource, RedisCommandReplayer replayer) {
        this.localSource = localSource;
        this.replayer = replayer;
        if (localSource == null || localSource.trim().isEmpty()) {
            logger.warn("The local source of the KafkaRedisCommandConsumer is not set, all the consumed commands "
                    + "will be replayed, including the commands produced by the local data center");
        }
        ContainerProperties containerProperties = new ContainerProperties(topic);
        containerProperties.setMessageListener((MessageListener<String, RedisCommandMessage>) record -> onMessage(record.value()));
        this.container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties);
        // Retry a failed replay and skip the record after the retries were exhausted
        this.container.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3)));
    }

    /**
     * Starts the underlying Kafka listener container.
     */
    public void start() {
        logger.info("Starting the PolyCache Kafka consumer, local source : {}", localSource);
        container.start();
    }

    void onMessage(RedisCommandMessage message) {
        if (!shouldReplay(message, localSource)) {
            return;
        }
        try {
            // Wait for the completion of the replay, so that the commands of a partition are
            // applied in order and the offsets are committed only after the replay completed
            replayer.replay(message).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The replay of the Redis command [" + message.getCommandName() + "] was interrupted", e);
        } catch (ExecutionException e) {
            // Propagate to the container error handler, which retries the record
            throw new IllegalStateException("Failed to replay the Redis command [" + message.getCommandName() + "]", e.getCause());
        }
    }

    /**
     * Decides whether the given message should be replayed into the local Redis. The messages of
     * the local data center are skipped, because they are already applied locally.
     *
     * @param message     the consumed message
     * @param localSource the source of the local data center
     * @return {@code true} if the message should be replayed
     */
    static boolean shouldReplay(RedisCommandMessage message, String localSource) {
        if (message == null) {
            return false;
        }
        // The command was produced by the local data center, it is already applied
        return !Objects.equals(localSource, message.getSource());
    }

    @Override
    public void close() {
        container.stop();
    }
}
