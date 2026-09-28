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
package io.github.loveeleve.polyzone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.github.loveeleve.polyzone.Strings.isNotBlank;
import static io.github.loveeleve.polyzone.ZoneConstants.ZONE_PROPERTY_NAME;

/**
 * The handler to attach the current zone into the metadata of a registration (Nacos, Eureka, ...).
 *
 * <p>
 * Once the zone is attached, downstream consumers can resolve the zone of every service instance
 * from the registry metadata and apply zone preference.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ZoneContext
 * @since 1.0.0
 */
public class ZoneAttachmentHandler {

    private static final Logger logger = LoggerFactory.getLogger(ZoneAttachmentHandler.class);

    private final ZoneContext zoneContext;

    public ZoneAttachmentHandler(ZoneContext zoneContext) {
        this.zoneContext = zoneContext;
    }

    /**
     * Attach the current zone into the given metadata.
     *
     * @param metadata the metadata of a registration
     */
    public void attachZone(Map<String, String> metadata) {
        String zone = zoneContext.getZone();
        if (isNotBlank(zone)) {
            String propertyName = ZONE_PROPERTY_NAME;
            // If the metadata is unmodifiable, UnsupportedOperationException will be thrown.
            try {
                metadata.put(propertyName, zone);
                logger.info("The zone ['{}'] has been attached into meta-data [name : '{}']", zone, propertyName);
            } catch (Throwable e) {
                logger.warn("The zone ['{}'] can't be attached into meta-data [name : '{}']", zone, propertyName);
            }
        } else {
            logger.warn("No zone info can be found in the context!");
        }
    }
}
