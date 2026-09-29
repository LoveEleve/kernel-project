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
package io.github.loveeleve.polycache.redisson;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A disposable local {@code redis-server} process bound to a random free port, used by the tests.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class RedisServer implements AutoCloseable {

    private final Process process;

    private final int port;

    private final Path directory;

    private RedisServer(Process process, int port, Path directory) {
        this.process = process;
        this.port = port;
        this.directory = directory;
    }

    public static RedisServer start() throws IOException {
        return start(List.of());
    }

    public static RedisServer start(List<String> extraArguments) throws IOException {
        int port = findFreePort();
        Path directory = Files.createTempDirectory("polycache-redis-");
        List<String> command = new ArrayList<>(List.of(
                "redis-server",
                "--port", String.valueOf(port),
                "--save", "",
                "--appendonly", "no",
                "--dir", directory.toString()));
        command.addAll(extraArguments);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = processBuilder.start();
        RedisServer redisServer = new RedisServer(process, port, directory);
        redisServer.awaitReady();
        return redisServer;
    }

    public String getAddress() {
        return "redis://127.0.0.1:" + port;
    }

    public int getPort() {
        return port;
    }

    private void awaitReady() throws IOException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                if (!process.isAlive()) {
                    throw new IllegalStateException("The redis-server exited unexpectedly", e);
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
        }
        throw new IllegalStateException("The redis-server is not ready on the port " + port);
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        deleteDirectory();
    }

    private void deleteDirectory() {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
