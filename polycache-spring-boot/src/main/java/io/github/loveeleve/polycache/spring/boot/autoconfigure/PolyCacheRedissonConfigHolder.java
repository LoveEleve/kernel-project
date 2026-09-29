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
import org.redisson.config.Config;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.core.Ordered;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Applies the {@link RedisCommandCapture} to the Redisson {@link Config} of the application, and
 * keeps a clone of the original configuration used to create the dedicated replay client.
 *
 * <p>The replay client must not capture the replayed commands, otherwise the commands received from
 * the other data centers would be published again and the replication would loop.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class PolyCacheRedissonConfigHolder implements RedissonAutoConfigurationCustomizer, Ordered {

    private final RedisCommandCapture capture;

    private final AtomicReference<Config> replayConfig = new AtomicReference<>();

    PolyCacheRedissonConfigHolder(RedisCommandCapture capture) {
        this.capture = capture;
    }

    @Override
    public void customize(Config config) {
        // The clone is created before the capture is installed, so the replay client does not capture
        replayConfig.compareAndSet(null, new Config(config));
        capture.applyTo(config);
    }

    /**
     * The customizer runs after the other customizers, so the cloned configuration of the replay
     * client includes their changes.
     *
     * @return the lowest precedence
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * @return the configuration of the replay client, {@code null} if {@link #customize(Config)}
     * was not called yet
     */
    Config getReplayConfig() {
        return replayConfig.get();
    }
}
