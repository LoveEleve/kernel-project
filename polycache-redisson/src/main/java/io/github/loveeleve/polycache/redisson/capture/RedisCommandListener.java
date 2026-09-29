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
package io.github.loveeleve.polycache.redisson.capture;

import io.github.loveeleve.polycache.command.RedisCommandMessage;

/**
 * The listener of the captured Redis commands. The {@link #onRedisCommand(RedisCommandMessage)}
 * callback is invoked on the Netty event loop of the Redisson connection, so implementations must
 * be thread-safe (the callback may be invoked concurrently by the multiple connections of the
 * client) and must not block the calling thread.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   RedisCommandListener listener = message -> {
 *       // Forward the message to the Kafka producer, the send is asynchronous
 *       kafkaTemplate.send(topic, message.getKey(), message);
 *   };
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@FunctionalInterface
public interface RedisCommandListener {

    /**
     * Callback on a captured Redis command, after the command was executed successfully.
     *
     * @param message the captured command
     */
    void onRedisCommand(RedisCommandMessage message);
}
