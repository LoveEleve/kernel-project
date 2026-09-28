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
package io.github.loveeleve.polyzone.spring.event;

import io.github.loveeleve.polyzone.ZoneContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ApplicationContextEvent;

import java.beans.PropertyChangeEvent;
import java.util.List;

import static java.util.Collections.unmodifiableList;

/**
 * The {@link ZoneContext} changed {@link ApplicationContextEvent event}.
 *
 * <p>
 * The event carries all {@link PropertyChangeEvent property change events} that happened during one
 * zone context change, so that listeners can tell which settings were actually changed.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class ZoneContextChangedEvent extends ApplicationContextEvent {

    private final ZoneContext zoneContext;

    private final List<PropertyChangeEvent> propertyChangeEvents;

    /**
     * Create a new event.
     *
     * @param context              the {@code ApplicationContext} that the event is raised for (must not be
     *                             {@code null})
     * @param zoneContext          {@link ZoneContext}
     * @param propertyChangeEvents {@link PropertyChangeEvent} List
     */
    public ZoneContextChangedEvent(ApplicationContext context, ZoneContext zoneContext,
                                   List<PropertyChangeEvent> propertyChangeEvents) {
        super(context);
        this.zoneContext = zoneContext;
        this.propertyChangeEvents = unmodifiableList(propertyChangeEvents);
    }

    public ZoneContext getZoneContext() {
        return zoneContext;
    }

    public List<PropertyChangeEvent> getPropertyChangeEvents() {
        return propertyChangeEvents;
    }
}
