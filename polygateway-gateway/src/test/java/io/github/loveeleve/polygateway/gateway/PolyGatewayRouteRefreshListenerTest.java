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

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PolyGatewayRouteRefreshListener} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class PolyGatewayRouteRefreshListenerTest {

    @Test
    void testHeartbeatRefreshesTheRoutes() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
        context.addApplicationListener(events::add);
        try {
            new PolyGatewayRouteRefreshListener(context).onApplicationEvent(new HeartbeatEvent("test", "value"));

            assertEquals(1, events.size());
            assertTrue(events.get(0) instanceof RefreshRoutesEvent);
        } finally {
            context.close();
        }
    }
}
