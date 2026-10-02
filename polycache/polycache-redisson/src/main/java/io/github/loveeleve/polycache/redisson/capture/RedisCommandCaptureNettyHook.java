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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import org.redisson.client.NettyHook;

/**
 * The {@link NettyHook} that installs the {@link CommandCaptureHandler} into the pipeline of every
 * Redisson connection.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see RedisCommandCapture#applyTo(org.redisson.config.Config)
 * @since 1.0.0
 */
class RedisCommandCaptureNettyHook implements NettyHook {

    private final RedisCommandCapture capture;

    RedisCommandCaptureNettyHook(RedisCommandCapture capture) {
        this.capture = capture;
    }

    @Override
    public void afterBoostrapInitialization(Bootstrap bootstrap) {
        // No-op
    }

    @Override
    public void afterChannelInitialization(Channel channel) {
        channel.pipeline().addLast(CommandCaptureHandler.NAME, new CommandCaptureHandler(capture));
    }
}
