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
import io.github.loveeleve.polycache.replicator.serialization.RedisCommandMessageSerializer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KafkaRedisCommandProducer} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class KafkaRedisCommandProducerTest {

    @Test
    void testCloseDestroysTheOwnedProducerFactory() {
        DestroyRecordingProducerFactory producerFactory = newProducerFactory();
        KafkaRedisCommandProducer producer = new KafkaRedisCommandProducer(producerFactory, "test-topic");

        producer.close();

        assertTrue(producerFactory.isDestroyed());
    }

    @Test
    void testCloseDoesNotDestroyTheProvidedTemplate() {
        DestroyRecordingProducerFactory producerFactory = newProducerFactory();
        KafkaTemplate<String, RedisCommandMessage> template = new KafkaTemplate<>(producerFactory);
        KafkaRedisCommandProducer producer = new KafkaRedisCommandProducer(template, "test-topic");

        producer.close();

        assertFalse(producerFactory.isDestroyed());
    }

    private static DestroyRecordingProducerFactory newProducerFactory() {
        return new DestroyRecordingProducerFactory(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, RedisCommandMessageSerializer.class));
    }

    /**
     * Records whether the factory was destroyed, without connecting to a broker.
     */
    static class DestroyRecordingProducerFactory extends DefaultKafkaProducerFactory<String, RedisCommandMessage> {

        private final AtomicBoolean destroyed = new AtomicBoolean();

        DestroyRecordingProducerFactory(Map<String, Object> configs) {
            super(configs);
        }

        @Override
        public void destroy() {
            destroyed.set(true);
            super.destroy();
        }

        boolean isDestroyed() {
            return destroyed.get();
        }
    }
}
