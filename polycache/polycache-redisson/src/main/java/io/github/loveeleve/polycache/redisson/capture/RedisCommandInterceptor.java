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
 * Extension point invoked for every captured Redis command, before the command is published. The
 * interceptors of a {@link RedisCommandCapture} form a chain : each interceptor receives the
 * message of the previous one (the original message by default) and may :
 *
 * <ul>
 * <li>return the message unchanged</li>
 * <li>return a modified message (e.g. enrich it, redact the arguments, rewrite the source)</li>
 * <li>return {@code null} to skip the command (e.g. filter some keys out of the replication)</li>
 * </ul>
 *
 * <p>Together with {@link RedisCommandListener}, the extension points cover the two phases of the
 * interception : the interceptors observe the command before its execution completes (and may
 * transform or drop it), the listener is notified after the command was executed successfully.
 *
 * <p>The interceptors are invoked on the Netty event loop of the Redisson connection, so they must
 * be thread-safe (the chain may run concurrently on the multiple connections of the client) and
 * must not block the calling thread. Each logical command is intercepted exactly once, even when
 * the Netty write path retries the command. An exception thrown by an interceptor skips the
 * capture of the command only, the Redis command itself is never affected.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   // Do not replicate the temporary keys
 *   RedisCommandInterceptor filter = message ->
 *           message.getKey() != null && message.getKey().startsWith("temp:") ? null : message;
 *
 *   RedisCommandCapture capture = RedisCommandCapture.builder()
 *           .source("zone-a")
 *           .listener(listener)
 *           .interceptor(filter)
 *           .build();
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see RedisCommandCapture#getInterceptors()
 * @since 1.0.0
 */
@FunctionalInterface
public interface RedisCommandInterceptor {

    /**
     * Intercepts the captured command.
     *
     * @param message the message of the captured command
     * @return the message to publish (may be the given message or a new one), {@code null} to skip
     * the command
     */
    RedisCommandMessage intercept(RedisCommandMessage message);
}
