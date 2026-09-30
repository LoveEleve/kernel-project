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
package io.github.loveeleve.polygateway.gateway;

import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;

/**
 * Refreshes the endpoint routes on every discovery heartbeat, so that the changes of the service
 * instances (the metadata changes included) are picked up without relying on the heartbeat value
 * change heuristic : the route definitions are always re-evaluated from the current discovery
 * state.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see WebEndpointRouteDefinitionLocator
 * @since 1.0.0
 */
public class PolyGatewayRouteRefreshListener implements ApplicationListener<HeartbeatEvent> {

    private final ApplicationEventPublisher eventPublisher;

    public PolyGatewayRouteRefreshListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onApplicationEvent(HeartbeatEvent event) {
        eventPublisher.publishEvent(new RefreshRoutesEvent(this));
    }
}
