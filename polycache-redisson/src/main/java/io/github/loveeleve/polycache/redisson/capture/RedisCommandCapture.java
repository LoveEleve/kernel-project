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
import io.github.loveeleve.polycache.command.RedisCommandRegistry;
import org.redisson.client.protocol.CommandData;
import org.redisson.client.protocol.CommandsData;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Transparent Redis command interception for Redisson. The capture is installed through
 * {@link Config#setNettyHook(org.redisson.client.NettyHook)}, so every command written by the
 * {@link org.redisson.api.RedissonClient} is observed on the connection pipeline without changing
 * the application code.
 *
 * <p>Only the commands accepted by the {@link #getCommandFilter() command filter} (the Redis write
 * commands by default) are captured, and the {@link RedisCommandListener listener} is notified
 * after the command was executed successfully.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   Config config = new Config();
 *   config.useSingleServer().setAddress("redis://127.0.0.1:6379");
 *
 *   RedisCommandCapture capture = RedisCommandCapture.builder()
 *           .source("zone-a")
 *           .listener(message -> kafkaTemplate.send("redis-replication", message.getKey(), message))
 *           .build();
 *   capture.applyTo(config);
 *
 *   RedissonClient client = Redisson.create(config);
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see CommandCaptureHandler
 * @since 1.0.0
 */
public class RedisCommandCapture {

    private static final Logger logger = LoggerFactory.getLogger(RedisCommandCapture.class);

    private final String source;

    private final RedisCommandListener listener;

    private final Predicate<String> commandFilter;

    private final boolean enabled;

    /**
     * The promises of the already captured commands (weakly referenced), so that a command retried
     * on the Netty write path is published only once.
     */
    private final Set<CompletableFuture<?>> capturedPromises =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private RedisCommandCapture(Builder builder) {
        this.source = builder.source;
        this.listener = Objects.requireNonNull(builder.listener, "The RedisCommandListener must not be null");
        this.commandFilter = builder.commandFilter;
        this.enabled = builder.enabled;
        if (source == null || source.trim().isEmpty()) {
            logger.warn("The source of the RedisCommandCapture is not set, the consumer side cannot detect "
                    + "and skip the commands produced by the local data center");
        }
    }

    /**
     * @return the source that produced the commands, e.g. the name of the data center
     */
    public String getSource() {
        return source;
    }

    /**
     * @return the listener of the captured commands
     */
    public RedisCommandListener getListener() {
        return listener;
    }

    /**
     * @return the filter deciding which commands are captured
     */
    public Predicate<String> getCommandFilter() {
        return commandFilter;
    }

    /**
     * @return the capture is enabled or not
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Installs this capture into the given Redisson {@link Config}. It must be called before the
     * {@link org.redisson.api.RedissonClient} is created.
     *
     * @param config the Redisson configuration
     */
    public void applyTo(Config config) {
        config.setNettyHook(new RedisCommandCaptureNettyHook(this));
    }

    void capture(CommandData<?, ?> commandData) {
        RedisCommandMessage message = message(commandData);
        if (message == null) {
            return;
        }
        CompletableFuture<?> promise = commandData.getPromise();
        if (promise == null) {
            publish(message);
            return;
        }
        captureOnCompletion(promise, List.of(message));
    }

    void capture(CommandsData commandsData) {
        if (!enabled) {
            return;
        }
        if (commandsData.isSkipResult()) {
            // With a batch executed with skipResult, Redisson does not complete the promises of the
            // inner commands, so they are published when the whole batch completes
            List<RedisCommandMessage> messages = new ArrayList<>();
            collectMessages(commandsData.getCommands(), messages);
            collectMessages(commandsData.getAttachedCommands(), messages);
            CompletableFuture<?> promise = commandsData.getPromise();
            if (promise == null) {
                messages.forEach(this::publish);
            } else {
                captureOnCompletion(promise, messages);
            }
            return;
        }
        List<CommandData<?, ?>> commands = commandsData.getCommands();
        if (commands != null) {
            commands.forEach(this::capture);
        }
        List<CommandData<?, ?>> attachedCommands = commandsData.getAttachedCommands();
        if (attachedCommands != null) {
            attachedCommands.forEach(this::capture);
        }
    }

    private RedisCommandMessage message(CommandData<?, ?> commandData) {
        if (!enabled) {
            return null;
        }
        String commandName = commandData.getCommand().getName();
        if (!commandFilter.test(commandName)) {
            return null;
        }
        return RedisCommandMessages.message(commandName, commandData.getParams(), source);
    }

    private void collectMessages(List<CommandData<?, ?>> commands, List<RedisCommandMessage> messages) {
        if (commands == null) {
            return;
        }
        for (CommandData<?, ?> command : commands) {
            RedisCommandMessage message = message(command);
            if (message != null) {
                messages.add(message);
            }
        }
    }

    private void captureOnCompletion(CompletableFuture<?> promise, List<RedisCommandMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        if (!capturedPromises.add(promise)) {
            // The command(s) were already captured, e.g. they were retried after a connection failure
            return;
        }
        // The command is replicated only when it was executed successfully
        promise.whenComplete((result, error) -> {
            if (error == null) {
                messages.forEach(this::publish);
            } else {
                logger.debug("The Redis command(s) [{}] were not replicated because of : {}",
                        messages.stream().map(RedisCommandMessage::getCommandName).collect(Collectors.joining(",")),
                        error.getMessage());
            }
        });
    }

    private void publish(RedisCommandMessage message) {
        try {
            listener.onRedisCommand(message);
        } catch (Throwable e) {
            logger.warn("The RedisCommandListener [{}] failed to handle the message : {}", listener.getClass().getName(), message, e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The builder of {@link RedisCommandCapture}.
     */
    public static class Builder {

        private String source;

        private RedisCommandListener listener;

        private Predicate<String> commandFilter = RedisCommandRegistry::isWriteCommand;

        private boolean enabled = true;

        /**
         * Sets the source that produced the commands, e.g. the name of the data center.
         *
         * @param source the source name
         * @return this builder
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * Sets the listener of the captured commands, required.
         *
         * @param listener the listener
         * @return this builder
         */
        public Builder listener(RedisCommandListener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * Sets the filter deciding which commands are captured. The Redis write commands are
         * captured by default.
         *
         * @param commandFilter the command filter
         * @return this builder
         */
        public Builder commandFilter(Predicate<String> commandFilter) {
            this.commandFilter = commandFilter;
            return this;
        }

        /**
         * Enables or disables the capture.
         *
         * @param enabled the capture is enabled or not
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public RedisCommandCapture build() {
            return new RedisCommandCapture(this);
        }
    }
}
