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
package io.github.loveeleve.polycache.redisson.routing;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A dynamic facade of multiple {@link RedissonClient} targets, routing every call to the target
 * selected on the current thread. Two capabilities distinguish it :
 *
 * <ul>
 * <li>The targets can be added, removed and <b>replaced at runtime</b>
 * ({@link #replaceTarget(String, Config)}), so a configuration change (for example a new address)
 * is applied without restarting the application</li>
 * <li>The target selection is <b>scoped</b> ({@link #runWithTarget(String, Runnable)} /
 * {@link #callWithTarget(String, Supplier)}) : the previous selection is always restored, so a
 * pooled request thread never keeps another request's target</li>
 * </ul>
 *
 * <p>A request (or a task) either uses the scoped API, or calls {@link #switchTarget(String)} and
 * guarantees the {@link #clearTarget()} in a {@code finally} block.
 *
 * <p>The routing client returned by {@link #getClient()} is a JDK dynamic proxy over
 * {@link RedissonClient} : Redisson allows a custom {@link org.redisson.connection.ConnectionManager}
 * through {@code Config#useCustomServers}, but the {@code Config} copy constructor of Redisson
 * 3.27/3.35 does not copy that field, so the custom connection manager is silently dropped by
 * {@code Redisson.create(Config)} (verified by test). Routing at the client facade is version
 * independent and does not touch the Redisson internals.
 *
 * <p>The routing is resolved when a call is made on the routing client : the data structures
 * ({@code RBucket}, {@code RMap}...) returned by a call are bound to the target client of that
 * call, so the operations on these objects keep using that target even after the target selection
 * ends.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   DynamicRedissonClient dynamic = DynamicRedissonClient.create(primaryConfig);
 *   dynamic.addTarget("zone-us", usConfig);
 *
 *   RedissonClient client = dynamic.getClient();
 *   client.getBucket("user:1").set("alice");        // the default target
 *
 *   dynamic.runWithTarget("zone-us", () -> {        // the "zone-us" target, restored afterwards
 *       client.getBucket("user:1").set("alice");
 *   });
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class DynamicRedissonClient implements AutoCloseable {

    /**
     * The name of the default target, created by {@link #create(Config)}.
     */
    public static final String DEFAULT_TARGET = "default";

    private static final Logger logger = LoggerFactory.getLogger(DynamicRedissonClient.class);

    private final Map<String, Target> targets = new ConcurrentHashMap<>();

    private final ThreadLocal<String> currentTarget = new ThreadLocal<>();

    private final AtomicBoolean shutdown = new AtomicBoolean();

    private final RedissonClient routingClient;

    private volatile String defaultTarget = DEFAULT_TARGET;

    private DynamicRedissonClient(Target target) {
        this.targets.put(DEFAULT_TARGET, target);
        this.routingClient = createRoutingClient();
    }

    /**
     * Creates the router initialized with a default target created from the given configuration.
     * The client is owned by the router and is shut down with it.
     *
     * @param defaultConfig the configuration of the default target
     * @return a new router
     */
    public static DynamicRedissonClient create(Config defaultConfig) {
        return new DynamicRedissonClient(Target.owned(Objects.requireNonNull(defaultConfig, "The default configuration must not be null")));
    }

    /**
     * Creates the router initialized with a default target. The client is <b>not</b> shut down by
     * the router, its lifecycle stays owned by the caller.
     *
     * @param defaultClient the default client
     * @return a new router
     */
    public static DynamicRedissonClient create(RedissonClient defaultClient) {
        return new DynamicRedissonClient(Target.shared(Objects.requireNonNull(defaultClient, "The default client must not be null")));
    }

    /**
     * @return the routing client, every call of which is routed to the target of the current thread
     */
    public RedissonClient getClient() {
        return routingClient;
    }

    /**
     * Registers a new target created from the given configuration, or replaces the target of the
     * same name. The client is owned by the router and is shut down when the target is removed or
     * replaced.
     *
     * @param name   the name of the target
     * @param config the configuration of the target
     * @return this router
     */
    public DynamicRedissonClient addTarget(String name, Config config) {
        requireName(name);
        requireActive();
        return addTarget(name, Target.owned(Objects.requireNonNull(config, "The configuration must not be null")));
    }

    /**
     * Registers a new target, or replaces the target of the same name. The client is <b>not</b>
     * shut down by the router.
     *
     * @param name   the name of the target
     * @param client the client of the target
     * @return this router
     */
    public DynamicRedissonClient addTarget(String name, RedissonClient client) {
        requireName(name);
        requireActive();
        return addTarget(name, Target.shared(Objects.requireNonNull(client, "The client must not be null")));
    }

    /**
     * Replaces the existing target at runtime. The previous client is shut down (when owned by the
     * router) after the swap; the operations in flight on the previous client may fail.
     *
     * @param name   the name of the target
     * @param config the new configuration of the target
     * @return this router
     */
    public DynamicRedissonClient replaceTarget(String name, Config config) {
        requireActive();
        requireTarget(name);
        return addTarget(name, Target.owned(Objects.requireNonNull(config, "The configuration must not be null")));
    }

    /**
     * Replaces the existing target at runtime with the given client.
     *
     * @param name   the name of the target
     * @param client the new client of the target
     * @return this router
     */
    public DynamicRedissonClient replaceTarget(String name, RedissonClient client) {
        requireActive();
        requireTarget(name);
        return addTarget(name, Target.shared(Objects.requireNonNull(client, "The client must not be null")));
    }

    /**
     * Removes the target and shuts it down (when owned by the router). The default target cannot
     * be removed.
     *
     * @param name the name of the target
     * @return this router
     */
    public DynamicRedissonClient removeTarget(String name) {
        if (DEFAULT_TARGET.equals(name)) {
            throw new IllegalArgumentException("The default target cannot be removed");
        }
        Target removed = targets.remove(name);
        if (removed != null) {
            removed.shutdown();
        }
        return this;
    }

    /**
     * Sets the default target used when no target is selected on the current thread.
     *
     * @param name the name of the registered target
     * @return this router
     */
    public DynamicRedissonClient setDefaultTarget(String name) {
        requireTarget(name);
        this.defaultTarget = name;
        return this;
    }

    /**
     * @return the name of the default target
     */
    public String getDefaultTarget() {
        return defaultTarget;
    }

    /**
     * @return the names of the registered targets
     */
    public Set<String> getTargetNames() {
        return Collections.unmodifiableSet(targets.keySet());
    }

    /**
     * Selects the target of the current thread for the subsequent calls of the routing client. It
     * must be paired with {@link #clearTarget()}, typically in a {@code finally} block, otherwise
     * the pooled thread keeps the selection. Prefer the scoped API
     * ({@link #runWithTarget(String, Runnable)} / {@link #callWithTarget(String, Supplier)}).
     *
     * @param name the name of the registered target
     */
    public void switchTarget(String name) {
        requireActive();
        requireTarget(name);
        currentTarget.set(name);
    }

    /**
     * Clears the target selected on the current thread.
     */
    public void clearTarget() {
        currentTarget.remove();
    }

    /**
     * Runs the action with the given target selected on the current thread. The previous selection
     * is restored afterwards, so the scopes are nestable and a pooled thread never leaks the
     * selection.
     *
     * @param name   the name of the registered target
     * @param action the action to run
     */
    public void runWithTarget(String name, Runnable action) {
        Objects.requireNonNull(action, "The action must not be null");
        callWithTarget(name, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Calls the supplier with the given target selected on the current thread. The previous
     * selection is restored afterwards, so the scopes are nestable and a pooled thread never leaks
     * the selection.
     *
     * @param name     the name of the registered target
     * @param supplier the supplier to call
     * @param <T>      the result type
     * @return the result of the supplier
     */
    public <T> T callWithTarget(String name, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "The supplier must not be null");
        String previousTarget = getCurrentTarget();
        switchTarget(name);
        try {
            return supplier.get();
        } finally {
            if (previousTarget == null) {
                clearTarget();
            } else {
                currentTarget.set(previousTarget);
            }
        }
    }

    /**
     * @return the name of the target selected on the current thread, {@code null} if none
     */
    public String getCurrentTarget() {
        return currentTarget.get();
    }

    /**
     * @return the client currently in charge of the operations
     */
    public RedissonClient getCurrentClient() {
        requireActive();
        String name = currentTarget.get();
        if (name != null) {
            Target selected = targets.get(name);
            if (selected != null) {
                return selected.client;
            }
            logger.warn("The selected Redis target [{}] is not registered, the default target [{}] is used", name, defaultTarget);
            // The target was removed (e.g. by another thread), do not keep the stale selection
            currentTarget.remove();
        }
        return targets.get(defaultTarget).client;
    }

    /**
     * @return the routing client is shut down or not
     */
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Shuts down the router and the targets owned by it.
     */
    public void shutdown() {
        shutdown(2, 15, TimeUnit.SECONDS);
    }

    /**
     * Shuts down the router and the targets owned by it.
     *
     * @param quietPeriod the quiet period of the graceful shutdown
     * @param timeout     the timeout of the graceful shutdown
     * @param unit        the unit of the quiet period and the timeout
     */
    public void shutdown(long quietPeriod, long timeout, TimeUnit unit) {
        if (shutdown.compareAndSet(false, true)) {
            targets.values().forEach(target -> target.shutdown(quietPeriod, timeout, unit));
            targets.clear();
        }
    }

    private RedissonClient createRoutingClient() {
        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                this::invoke);
    }

    private Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "DynamicRedissonClient";
            case "isShutdown":
                return isShutdown();
            case "shutdown":
                if (args == null || args.length == 0) {
                    shutdown();
                } else {
                    shutdown((Long) args[0], (Long) args[1], (TimeUnit) args[2]);
                }
                return null;
            default:
                try {
                    return method.invoke(getCurrentClient(), args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
        }
    }

    private DynamicRedissonClient addTarget(String name, Target target) {
        Target previous = targets.put(name, target);
        if (previous != null) {
            previous.shutdown();
        }
        return this;
    }

    private void requireName(String name) {
        Objects.requireNonNull(name, "The name of the target must not be null");
    }

    private void requireTarget(String name) {
        requireName(name);
        if (!targets.containsKey(name)) {
            throw new IllegalArgumentException("The target [" + name + "] is not registered");
        }
    }

    private void requireActive() {
        if (shutdown.get()) {
            throw new IllegalStateException("The DynamicRedissonClient has been shut down");
        }
    }

    /**
     * A registered target, the client created from a configuration is owned by the router.
     */
    private static class Target {

        private final RedissonClient client;

        private final boolean owned;

        private Target(RedissonClient client, boolean owned) {
            this.client = client;
            this.owned = owned;
        }

        static Target owned(Config config) {
            return new Target(Redisson.create(config), true);
        }

        static Target shared(RedissonClient client) {
            return new Target(client, false);
        }

        void shutdown() {
            shutdown(2, 15, TimeUnit.SECONDS);
        }

        void shutdown(long quietPeriod, long timeout, TimeUnit unit) {
            if (owned) {
                try {
                    client.shutdown(quietPeriod, timeout, unit);
                } catch (Throwable e) {
                    logger.warn("Failed to shut down a Redis target client", e);
                }
            }
        }
    }
}
