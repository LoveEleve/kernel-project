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
package io.github.loveeleve.polyconfig.spring.event;

import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

/**
 * The publisher of {@link ConfigPropertiesChangedEvent}, used by the PolyConfig backend adapters to
 * publish configuration changes in a unified way.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ConfigPropertiesChangedEvent
 * @since 1.0.0
 */
public class ConfigChangePublisher {

    private final ApplicationEventPublisher eventPublisher;

    public ConfigChangePublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publish a configuration properties changed event.
     *
     * @param sourceName the name of the configuration source
     * @param changes    the property changes; publishing is skipped when empty
     */
    public void publish(String sourceName, List<ConfigPropertyChangedEvent> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        eventPublisher.publishEvent(new ConfigPropertiesChangedEvent(eventPublisher, sourceName, changes));
    }
}
