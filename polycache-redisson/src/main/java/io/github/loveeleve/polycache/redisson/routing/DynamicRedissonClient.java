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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A dynamic facade of multiple {@link RedissonClient} targets, routing every call to the target
 * selected on the current thread. It is the Redisson equivalent of the
 * {@code DynamicRedisConnectionFactory} of microsphere-redis : a request (or a task) calls
 * {@link #switchTarget(String)}, performs the Redis operations through {@link #getClient()}, and
 * {@link #clearTarget()} restores the default target. The targets can be added, removed and
 * <b>replaced at runtime</b> ({@link #replaceTarget(String, Config)}), so a configuration change
 * (for example a new address) is applied without restarting the application.
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
 * call, so the operations on these objects keep using that target even after
 * {@link #clearTarget()}.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   DynamicRedissonClient dynamic = DynamicRedissonClient.create(primaryConfig);
 *   dynamic.addTarget("zone-us", usConfig);
 *
 *   RedissonClient client = dynamic.getClient();
 *   client.getBucket("user:1").set("alice");        // the default target
 *   dynamic.switchTarget("zone-us");                // inside a request / a task
 *   try {
 *       client.getBucket("user:1").set("alice");    // the "zone-us" target
 *   } finally {
 *       dynamic.clearTarget();
 *   }
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

    private DynamicRedissonClient(Target defaultTarget) {
        this.targets.put(DEFAULT_TARGET, defaultTarget);
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
        return new DynamicRedissonClient(Target.owned(defaultConfig));
    }

    /**
     * Creates the router initialized with a default target. The client is <b>not</b> shut down by
     * the router, its lifecycle stays owned by the caller.
     *
     * @param defaultClient the default client
     * @return a new router
     */
    public static DynamicRedissonClient create(RedissonClient defaultClient) {
        return new DynamicRedissonClient(Target.shared(defaultClient));
    }

    /**
     * @return the routing client, every call of which is routed to the target of the current thread
     */
    public RedissonClient getClient() {
        return routingClient;
    }

    /**
     * Registers a new target created from the given configuration. The client is owned by the
     * router and is shut down when the target is removed or replaced.
     *
     * @param name   the name of the target
     * @param config the configuration of the target
     * @return this router
     */
    public DynamicRedissonClient addTarget(String name, Config config) {
        return addTarget(name, Target.owned(config));
    }

    /**
     * Registers a new target. The client is <b>not</b> shut down by the router.
     *
     * @param name   the name of the target
     * @param client the client of the target
     * @return this router
     */
    public DynamicRedissonClient addTarget(String name, RedissonClient client) {
        return addTarget(name, Target.shared(client));
    }

    private DynamicRedissonClient addTarget(String name, Target target) {
        Objects.requireNonNull(name, "The name of the target must not be null");
        Target previous = targets.put(name, target);
        if (previous != null) {
            previous.shutdown();
        }
        return this;
    }

    /**
     * Replaces the target at runtime. The previous client is shut down (when owned by the router)
     * after the swap; the operations in flight on the previous client may fail.
     *
     * @param name   the name of the target
     * @param config the new configuration of the target
     * @return this router
     */
    public DynamicRedissonClient replaceTarget(String name, Config config) {
        return addTarget(name, Target.owned(config));
    }

    /**
     * Replaces the target at runtime with the given client.
     *
     * @param name   the name of the target
     * @param client the new client of the target
     * @return this router
     */
    public DynamicRedissonClient replaceTarget(String name, RedissonClient client) {
        return addTarget(name, Target.shared(client));
    }

    /**
     * Removes the target. The default target cannot be removed.
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
     * the pooled thread keeps the selection.
     *
     * @param name the name of the registered target
     */
    public void switchTarget(String name) {
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
     * @return the name of the target selected on the current thread, {@code null} if none
     */
    public String getCurrentTarget() {
        return currentTarget.get();
    }

    /**
     * @return the client currently in charge of the operations
     */
    public RedissonClient getCurrentClient() {
        String name = currentTarget.get();
        if (name != null) {
            Target selected = targets.get(name);
            if (selected != null) {
                return selected.client;
            }
            logger.warn("The selected Redis target [{}] is not registered, the default target [{}] is used", name, defaultTarget);
        }
        Target target = targets.get(defaultTarget);
        if (target == null) {
            throw new IllegalStateException("The Redis target [" + defaultTarget + "] is not registered");
        }
        return target.client;
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
        if (shutdown.compareAndSet(false, true)) {
            targets.values().forEach(Target::shutdown);
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
        String methodName = method.getName();
        switch (methodName) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "DynamicRedissonClient";
            case "isShutdown":
                return isShutdown();
            case "shutdown":
                shutdown();
                return null;
            case "getConfig":
                return ((Redisson) getCurrentClient()).getConfig();
            case "getConnectionManager":
                return ((Redisson) getCurrentClient()).getConnectionManager();
            default:
                try {
                    return method.invoke(getCurrentClient(), args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
        }
    }

    private void requireTarget(String name) {
        if (!targets.containsKey(name)) {
            throw new IllegalArgumentException("The target [" + name + "] is not registered");
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
            if (owned) {
                try {
                    client.shutdown();
                } catch (Throwable e) {
                    logger.warn("Failed to shut down a Redis target client", e);
                }
            }
        }
    }
}
