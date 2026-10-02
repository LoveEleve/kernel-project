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
package io.github.loveeleve.polyzone.spring.cloud.autoconfigure;

import io.github.loveeleve.polyzone.ZoneAttachmentHandler;
import io.github.loveeleve.polyzone.ZoneContext;
import io.github.loveeleve.polyzone.spring.boot.autoconfigure.ZoneAutoConfiguration;
import io.github.loveeleve.polyzone.spring.boot.condition.ConditionalOnZoneAvailable;
import io.github.loveeleve.polyzone.spring.cloud.event.ZoneAttachmentListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Zone Auto-Configuration for Spring Cloud.
 *
 * <p>
 * It attaches the current zone into the registration metadata through
 * {@link ZoneAttachmentListener}, so that the zone of every instance is visible in the registry.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ZoneAutoConfiguration
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnZoneAvailable
@ConditionalOnClass(name = {
        "org.springframework.cloud.client.discovery.event.InstancePreRegisteredEvent",
        "org.springframework.cloud.client.serviceregistry.Registration"
})
@ConditionalOnBean(ZoneContext.class)
@ConditionalOnProperty(value = "spring.cloud.service-registry.auto-registration.enabled", matchIfMissing = true)
@AutoConfigureAfter(ZoneAutoConfiguration.class)
@Import(value = {
        ZoneAttachmentHandler.class
})
public class ZoneCloudAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ZoneAttachmentListener zoneAttachmentListener(ZoneAttachmentHandler zoneAttachmentHandler) {
        return new ZoneAttachmentListener(zoneAttachmentHandler);
    }

}
