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
package io.github.loveeleve.polycache.replicator.producer;

import io.github.loveeleve.polycache.command.RedisCommandMessage;
import io.github.loveeleve.polycache.redisson.capture.RedisCommandListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@link RedisCommandListener} that produces the captured commands to Kafka.
 *
 * <p>The listener is invoked on the Netty event loop of the Redisson connection, so the messages
 * are enqueued and sent by a dedicated sender thread instead of blocking the event loop on the
 * Kafka metadata/producer operations. The single sender thread preserves the publishing order.
 * When the queue is full (the replication consumer is too slow), the new messages are dropped and
 * counted, and a warning is logged.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   ProducerFactory<String, RedisCommandMessage> producerFactory = new DefaultKafkaProducerFactory<>(configs);
 *   KafkaRedisCommandProducer producer = new KafkaRedisCommandProducer(producerFactory, "redis-replication");
 *
 *   RedisCommandCapture capture = RedisCommandCapture.builder()
 *           .source("zone-a")
 *           .listener(producer)
 *           .build();
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class KafkaRedisCommandProducer implements RedisCommandListener, AutoCloseable {

    /**
     * The default capacity of the sending queue.
     */
    public static final int DEFAULT_QUEUE_CAPACITY = 65536;

    private static final Logger logger = LoggerFactory.getLogger(KafkaRedisCommandProducer.class);

    private final KafkaTemplate<String, RedisCommandMessage> kafkaTemplate;

    private final String topic;

    private final BlockingQueue<RedisCommandMessage> sendingQueue;

    private final AtomicLong sentMessageCount = new AtomicLong();

    private final AtomicLong droppedMessageCount = new AtomicLong();

    private final Thread senderThread;

    private final ProducerFactory<String, RedisCommandMessage> ownedProducerFactory;

    private volatile boolean running = true;

    /**
     * Creates a producer owning the given factory : the factory (and the Kafka producers behind
     * it) is destroyed when {@link #close()} is called. The {@code KafkaTemplate} does not
     * destroy an externally provided factory, so the producer releases it explicitly.
     *
     * @param producerFactory the producer factory
     * @param topic           the topic
     */
    public KafkaRedisCommandProducer(ProducerFactory<String, RedisCommandMessage> producerFactory, String topic) {
        this(new KafkaTemplate<>(producerFactory), topic, DEFAULT_QUEUE_CAPACITY, producerFactory);
    }

    /**
     * Creates a producer over a caller managed {@link KafkaTemplate} : the template is not
     * destroyed when {@link #close()} is called.
     *
     * @param kafkaTemplate the Kafka template
     * @param topic         the topic
     */
    public KafkaRedisCommandProducer(KafkaTemplate<String, RedisCommandMessage> kafkaTemplate, String topic) {
        this(kafkaTemplate, topic, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * Creates a producer over a caller managed {@link KafkaTemplate}.
     *
     * @param kafkaTemplate the Kafka template
     * @param topic         the topic
     * @param queueCapacity the capacity of the sending queue
     */
    public KafkaRedisCommandProducer(KafkaTemplate<String, RedisCommandMessage> kafkaTemplate, String topic, int queueCapacity) {
        this(kafkaTemplate, topic, queueCapacity, null);
    }

    private KafkaRedisCommandProducer(KafkaTemplate<String, RedisCommandMessage> kafkaTemplate, String topic,
                                      int queueCapacity, ProducerFactory<String, RedisCommandMessage> ownedProducerFactory) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.ownedProducerFactory = ownedProducerFactory;
        this.sendingQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.senderThread = new Thread(this::sendLoop, "polycache-kafka-sender");
        this.senderThread.setDaemon(true);
        this.senderThread.start();
    }

    @Override
    public void onRedisCommand(RedisCommandMessage message) {
        if (!running) {
            logger.debug("The producer is closed, the Redis command [{}] is ignored", message.getCommandName());
            return;
        }
        if (!sendingQueue.offer(message)) {
            long dropped = droppedMessageCount.incrementAndGet();
            logger.warn("The sending queue is full, the Redis command [{}] of the source [{}] was dropped, "
                            + "total dropped : {}", message.getCommandName(), message.getSource(), dropped);
        }
    }

    /**
     * @return the number of the messages sent to Kafka successfully
     */
    public long getSentMessageCount() {
        return sentMessageCount.get();
    }

    /**
     * @return the number of the dropped messages due to the full sending queue
     */
    public long getDroppedMessageCount() {
        return droppedMessageCount.get();
    }

    private void sendLoop() {
        while (running || !sendingQueue.isEmpty()) {
            try {
                RedisCommandMessage message = sendingQueue.poll(100, TimeUnit.MILLISECONDS);
                if (message != null) {
                    send(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                logger.warn("Unexpected error in the Kafka sender thread", e);
            }
        }
    }

    private void send(RedisCommandMessage message) {
        try {
            kafkaTemplate.send(topic, partitionKey(message), message)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            logger.warn("Failed to send the Redis command [{}] of the source [{}] to the topic [{}]",
                                    message.getCommandName(), message.getSource(), topic, error);
                        } else {
                            sentMessageCount.incrementAndGet();
                            logger.debug("The Redis command [{}] was sent to the topic [{}]", message.getCommandName(), topic);
                        }
                    });
        } catch (Throwable e) {
            droppedMessageCount.incrementAndGet();
            logger.warn("Failed to send the Redis command [{}] of the source [{}]", message.getCommandName(), message.getSource(), e);
        }
    }

    private static String partitionKey(RedisCommandMessage message) {
        if (message.getKey() != null) {
            return message.getKey();
        }
        return message.getKeyBytes() == null ? null : Base64.getEncoder().encodeToString(message.getKeyBytes());
    }

    @Override
    public void close() {
        // The pending messages are drained before the sender thread is stopped
        running = false;
        try {
            senderThread.join(TimeUnit.SECONDS.toMillis(5));
            if (senderThread.isAlive()) {
                senderThread.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (ownedProducerFactory != null) {
            destroyProducerFactory();
        }
    }

    private void destroyProducerFactory() {
        try {
            if (ownedProducerFactory instanceof DefaultKafkaProducerFactory) {
                ((DefaultKafkaProducerFactory<String, RedisCommandMessage>) ownedProducerFactory).destroy();
            } else if (ownedProducerFactory instanceof AutoCloseable) {
                ((AutoCloseable) ownedProducerFactory).close();
            }
        } catch (Throwable e) {
            logger.warn("Failed to destroy the Kafka producer factory", e);
        }
    }
}
