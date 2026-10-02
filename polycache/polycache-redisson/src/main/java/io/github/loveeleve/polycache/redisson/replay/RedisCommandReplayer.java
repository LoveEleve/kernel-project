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
package io.github.loveeleve.polycache.redisson.replay;

import io.github.loveeleve.polycache.command.RedisCommandMessage;
import org.redisson.Redisson;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.decoder.MultiDecoder;
import org.redisson.command.CommandAsyncExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Replays a captured {@link RedisCommandMessage} against a target {@link RedissonClient} by
 * submitting the command to its {@link CommandAsyncExecutor}.
 *
 * <p>The arguments of the message are already encoded by the codec of the source client, so they
 * are submitted with the {@link ByteArrayCodec} without any dependency on the source codec. The
 * response of the replayed command is decoded by a permissive decoder, because the result is not
 * used on the consumer side.
 *
 * <p>The client used to construct the replayer should not enable the
 * {@link io.github.loveeleve.polycache.redisson.capture.RedisCommandCapture}, otherwise the
 * replayed commands would be captured again and the replication would loop.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   RedissonClient target = Redisson.create(targetConfig);
 *   RedisCommandReplayer replayer = new RedisCommandReplayer(target);
 *   replayer.replay(message).get();
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class RedisCommandReplayer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedisCommandReplayer.class);

    private static final MultiDecoder<Object> PERMISSIVE_DECODER = (parts, state) -> parts.size() == 1 ? parts.get(0) : parts;

    /**
     * The blocking commands cannot be replayed safely: the replay would block a connection and
     * consume another element of the collection, so they are skipped.
     */
    private static final Set<String> BLOCKING_COMMANDS = Set.of(
            "BLPOP", "BRPOP", "BLMOVE", "BLMPOP", "BRPOPLPUSH", "BZPOPMIN", "BZPOPMAX", "BZMPOP");

    private final RedissonClient redissonClient;

    private final CommandAsyncExecutor commandExecutor;

    private final boolean shutdownClientOnClose;

    /**
     * @param redissonClient the target client of the replay
     */
    public RedisCommandReplayer(RedissonClient redissonClient) {
        this(redissonClient, false);
    }

    /**
     * @param redissonClient        the target client of the replay
     * @param shutdownClientOnClose whether the client is shut down when this replayer is closed
     */
    public RedisCommandReplayer(RedissonClient redissonClient, boolean shutdownClientOnClose) {
        if (!(redissonClient instanceof Redisson)) {
            throw new IllegalArgumentException("The RedissonClient must be an instance of " + Redisson.class.getName());
        }
        this.redissonClient = redissonClient;
        this.commandExecutor = ((Redisson) redissonClient).getCommandExecutor();
        this.shutdownClientOnClose = shutdownClientOnClose;
    }

    /**
     * @return the target client of the replay
     */
    public RedissonClient getRedissonClient() {
        return redissonClient;
    }

    @Override
    public void close() {
        if (shutdownClientOnClose) {
            ((Redisson) redissonClient).shutdown();
        }
    }

    /**
     * Replays the given command against the target Redis, asynchronously. The returned future
     * completes when the command was applied, so the caller can serialize the replays of a
     * partition and acknowledge the consumed offset only after the replay completed.
     *
     * @param message the captured command
     * @return the completion of the replay, never {@code null}
     */
    public CompletableFuture<Void> replay(RedisCommandMessage message) {
        String commandName = message.getCommandName();
        if (isBlockingCommand(commandName)) {
            logger.warn("The blocking command [{}] of the source [{}] was skipped, it cannot be replayed safely",
                    commandName, message.getSource());
            return CompletableFuture.completedFuture(null);
        }
        String key = message.getKey();
        byte[] keyBytes = message.getKeyBytes();
        if (key == null && keyBytes == null) {
            logger.warn("The command [{}] of the source [{}] was skipped because it has no routing key",
                    commandName, message.getSource());
            return CompletableFuture.completedFuture(null);
        }

        RedisCommand<Object> command = new RedisCommand<>(commandName, PERMISSIVE_DECODER);
        Object[] params = message.getArgs();
        RFuture<Object> future = key != null
                ? commandExecutor.writeAsync(key, ByteArrayCodec.INSTANCE, command, params)
                : commandExecutor.writeAsync(keyBytes, ByteArrayCodec.INSTANCE, command, params);

        CompletableFuture<Void> replayFuture = future.toCompletableFuture().thenApply(result -> (Void) null);
        replayFuture.whenComplete((result, error) -> {
            if (error != null) {
                logger.warn("Failed to replay the command [{}] of the source [{}]", commandName, message.getSource(), error);
            } else {
                logger.debug("The command [{}] of the source [{}] was replayed successfully", commandName, message.getSource());
            }
        });
        return replayFuture;
    }

    /**
     * Tests whether the given command is a blocking command.
     *
     * @param commandName the name of the Redis command
     * @return {@code true} if the command is a blocking command
     */
    static boolean isBlockingCommand(String commandName) {
        return commandName != null && BLOCKING_COMMANDS.contains(commandName);
    }
}
