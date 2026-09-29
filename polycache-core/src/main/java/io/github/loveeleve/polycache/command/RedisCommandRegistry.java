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
package io.github.loveeleve.polycache.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Registry of the Redis commands, backed by the {@value #COMMANDS_RESOURCE} and
 * {@value #WRITE_COMMANDS_RESOURCE} classpath resources. The write commands are used by the
 * interception layer to decide whether a captured command should be replicated to the other
 * data centers.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   RedisCommandRegistry.isWriteCommand("SET");  // true
 *   RedisCommandRegistry.isWriteCommand("GET");  // false
 *   RedisCommandRegistry.getWriteCommands().size();
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class RedisCommandRegistry {

    /**
     * The classpath resource containing all Redis commands.
     */
    public static final String COMMANDS_RESOURCE = "META-INF/redis-commands";

    /**
     * The classpath resource containing the Redis write commands.
     */
    public static final String WRITE_COMMANDS_RESOURCE = "META-INF/redis-write-commands";

    private static final Logger logger = LoggerFactory.getLogger(RedisCommandRegistry.class);

    private static final Set<String> COMMANDS = load(COMMANDS_RESOURCE);

    private static final Set<String> WRITE_COMMANDS = load(WRITE_COMMANDS_RESOURCE);

    /**
     * @return the unmodifiable names of all Redis commands
     */
    public static Set<String> getCommands() {
        return COMMANDS;
    }

    /**
     * @return the unmodifiable names of the Redis write commands
     */
    public static Set<String> getWriteCommands() {
        return WRITE_COMMANDS;
    }

    /**
     * Tests whether the given command name is a Redis write command.
     *
     * @param commandName the name of the Redis command, e.g. {@code "SET"}
     * @return {@code true} if the command is a write command
     */
    public static boolean isWriteCommand(String commandName) {
        return commandName != null && WRITE_COMMANDS.contains(commandName.toUpperCase(Locale.ROOT));
    }

    /**
     * Tests whether the given command name is a known Redis command.
     *
     * @param commandName the name of the Redis command, e.g. {@code "SET"}
     * @return {@code true} if the command is known
     */
    public static boolean isCommand(String commandName) {
        return commandName != null && COMMANDS.contains(commandName.toUpperCase(Locale.ROOT));
    }

    private static Set<String> load(String resourceName) {
        Set<String> commands = new LinkedHashSet<>();
        ClassLoader classLoader = RedisCommandRegistry.class.getClassLoader();
        try {
            Enumeration<URL> resources = classLoader.getResources(resourceName);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                try (InputStream inputStream = resource.openStream()) {
                    load(inputStream, commands);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load the Redis commands from the resource : {}", resourceName, e);
        }
        return Collections.unmodifiableSet(commands);
    }

    private static void load(InputStream inputStream, Set<String> commands) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String command = line.trim();
                if (!command.isEmpty() && !command.startsWith("#")) {
                    commands.add(command.toUpperCase(Locale.ROOT));
                }
            }
        }
    }
}
