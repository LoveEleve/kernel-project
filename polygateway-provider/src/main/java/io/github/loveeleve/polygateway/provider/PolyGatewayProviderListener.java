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
package io.github.loveeleve.polygateway.provider;

import org.springframework.cloud.client.discovery.event.InstancePreRegisteredEvent;
import org.springframework.context.ApplicationListener;

/**
 * The provider side listener: when the service instance is about to be registered, the web
 * endpoints of the application are published into the instance metadata by the
 * {@link WebEndpointMetadataPublisher}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see WebEndpointMetadataPublisher
 * @since 1.0.0
 */
public class PolyGatewayProviderListener implements ApplicationListener<InstancePreRegisteredEvent> {

    private final WebEndpointMetadataPublisher publisher;

    public PolyGatewayProviderListener(WebEndpointMetadataPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void onApplicationEvent(InstancePreRegisteredEvent event) {
        publisher.publish(event.getRegistration());
    }
}
