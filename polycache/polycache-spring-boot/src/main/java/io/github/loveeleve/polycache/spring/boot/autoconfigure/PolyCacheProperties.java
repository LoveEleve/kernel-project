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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The configuration properties of PolyCache, bound to the {@code polycache.replication} prefix.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   polycache:
 *     replication:
 *       enabled: true
 *       source: zone-a
 *       topic: polycache-redis-replication
 *       consumer:
 *         enabled: true
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = PolyCacheProperties.PREFIX)
public class PolyCacheProperties {

    /**
     * The prefix of the PolyCache configuration properties.
     */
    public static final String PREFIX = "polycache.replication";

    /**
     * Enables the cross-datacenter replication.
     */
    private boolean enabled = false;

    /**
     * The source (data center) that produced the commands, required when the replication is enabled.
     */
    private String source;

    /**
     * The Kafka topic of the replicated commands.
     */
    private String topic = "polycache-redis-replication";

    /**
     * The Kafka consumer group of the replication.
     */
    private String consumerGroup = "polycache-replication";

    private final Consumer consumer = new Consumer();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    /**
     * The consumer configuration of the replication.
     */
    public static class Consumer {

        /**
         * Enables the consumer that replicates the commands of the other data centers into the
         * local Redis.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
