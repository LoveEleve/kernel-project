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
package io.github.loveeleve.polyzone.spring.cloud.event;

import io.github.loveeleve.polyzone.ZoneAttachmentHandler;
import io.github.loveeleve.polyzone.ZoneContext;
import org.springframework.cloud.client.discovery.event.InstancePreRegisteredEvent;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.context.ApplicationListener;

import java.util.Map;

/**
 * A listener for the {@link InstancePreRegisteredEvent instance pre-registered event} to attach the
 * current zone into the registration metadata.
 *
 * <p>
 * The event is published by {@code AbstractAutoServiceRegistration#start()} before the instance is
 * registered, so the attached zone will be visible to every consumer of the registry (Nacos, Eureka,
 * Consul, ...).
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ZoneContext
 * @see InstancePreRegisteredEvent
 * @since 1.0.0
 */
public class ZoneAttachmentListener implements ApplicationListener<InstancePreRegisteredEvent> {

    private final ZoneAttachmentHandler zoneAttachmentHandler;

    public ZoneAttachmentListener(ZoneAttachmentHandler zoneAttachmentHandler) {
        this.zoneAttachmentHandler = zoneAttachmentHandler;
    }

    @Override
    public void onApplicationEvent(InstancePreRegisteredEvent event) {
        Registration registration = event.getRegistration();
        Map<String, String> metadata = registration.getMetadata();
        zoneAttachmentHandler.attachZone(metadata);
    }
}
