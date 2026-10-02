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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A disposable local Redis Cluster (three cluster-enabled {@code redis-server} processes) used by
 * the tests.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class RedisCluster implements AutoCloseable {

    private final List<RedisServer> nodes;

    private RedisCluster(List<RedisServer> nodes) {
        this.nodes = nodes;
    }

    public static RedisCluster start(int nodeCount) throws IOException, InterruptedException {
        List<RedisServer> nodes = new ArrayList<>(nodeCount);
        try {
            for (int i = 0; i < nodeCount; i++) {
                nodes.add(RedisServer.start(List.of(
                        "--cluster-enabled", "yes",
                        "--cluster-config-file", "nodes.conf",
                        "--cluster-node-timeout", "5000")));
            }
            createCluster(nodes);
            awaitClusterReady(nodes.get(0).getPort());
            return new RedisCluster(nodes);
        } catch (Throwable e) {
            nodes.forEach(RedisServer::close);
            throw e;
        }
    }

    private static void createCluster(List<RedisServer> nodes) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("redis-cli", "--cluster", "create"));
        nodes.forEach(node -> command.add("127.0.0.1:" + node.getPort()));
        command.add("--cluster-yes");
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("The Redis cluster could not be created");
        }
    }

    private static void awaitClusterReady(int port) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
        while (System.currentTimeMillis() < deadline) {
            Process process = new ProcessBuilder("redis-cli", "-p", String.valueOf(port), "cluster", "info")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(5, TimeUnit.SECONDS);
            if (output.contains("cluster_state:ok")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("The Redis cluster is not ready");
    }

    public List<String> getNodeAddresses() {
        return nodes.stream().map(node -> "redis://127.0.0.1:" + node.getPort()).toList();
    }

    @Override
    public void close() {
        nodes.forEach(RedisServer::close);
    }
}
