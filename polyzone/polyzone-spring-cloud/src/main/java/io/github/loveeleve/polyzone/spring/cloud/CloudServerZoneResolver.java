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
package io.github.loveeleve.polyzone.spring.cloud;

import io.github.loveeleve.polyzone.ZoneResolver;
import org.springframework.cloud.client.ServiceInstance;

import java.util.Map;

import static io.github.loveeleve.polyzone.ZoneConstants.ZONE_PROPERTY_NAME;

/**
 * {@link ZoneResolver} for the Spring Cloud {@link ServiceInstance}, resolving the zone from the
 * instance metadata that was attached by
 * {@link io.github.loveeleve.polyzone.ZoneAttachmentHandler}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class CloudServerZoneResolver implements ZoneResolver<ServiceInstance> {

    /**
     * Singleton {@link CloudServerZoneResolver}
     */
    public static final CloudServerZoneResolver INSTANCE = new CloudServerZoneResolver();

    @Override
    public String resolve(ServiceInstance serviceInstance) {
        Map<String, String> instanceMetadata = serviceInstance.getMetadata();
        if (instanceMetadata != null) {
            return instanceMetadata.get(ZONE_PROPERTY_NAME);
        }
        return null;
    }
}
