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
import org.junit.jupiter.api.Test;

import static io.github.loveeleve.polycache.replicator.consumer.KafkaRedisCommandConsumer.shouldReplay;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KafkaRedisCommandConsumer} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class KafkaRedisCommandConsumerTest {

    @Test
    void testShouldReplay() {
        RedisCommandMessage message = RedisCommandMessage.builder()
                .commandName("SET")
                .args(new byte[][]{"user:1".getBytes()})
                .source("zone-a")
                .build();

        assertFalse(shouldReplay(null, "zone-a"));
        assertFalse(shouldReplay(message, "zone-a"));
        assertTrue(shouldReplay(message, "zone-b"));
        assertTrue(shouldReplay(message, null));
    }
}
