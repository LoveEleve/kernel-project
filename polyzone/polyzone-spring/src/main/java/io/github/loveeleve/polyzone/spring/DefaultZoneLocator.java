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
package io.github.loveeleve.polyzone.spring;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE;
import static io.github.loveeleve.polyzone.ZoneConstants.ORIGINAL_ZONE;
import static io.github.loveeleve.polyzone.ZoneConstants.ZONE_PROPERTY_NAME;

/**
 * The default {@link ZoneLocator} implementation, locating the zone from the Spring property
 * {@value io.github.loveeleve.polyzone.ZoneConstants#ZONE_PROPERTY_NAME}.
 *
 * <p>
 * It always supports any environment and falls back to
 * {@value io.github.loveeleve.polyzone.ZoneConstants#DEFAULT_ZONE} when the property is absent, so
 * that it works as the last locator of the chain for local development.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class DefaultZoneLocator extends AbstractZoneLocator {

    public static final int DEFAULT_ORDER = 20;

    public DefaultZoneLocator() {
        super(DEFAULT_ORDER);
    }

    @Override
    public boolean supports(Environment environment) {
        return true;
    }

    @Override
    public String locate(Environment environment) {
        String zone = environment.getProperty(ZONE_PROPERTY_NAME);
        if (StringUtils.hasText(zone) && !ORIGINAL_ZONE.equalsIgnoreCase(zone)) {
            logger.info("The zone ['{}'] was located from the Spring Property [name: '{}']", zone, ZONE_PROPERTY_NAME);
        } else {
            if (StringUtils.hasText(zone)) {
                logger.info("The Spring Property [name : '{}'] is set to the marker value '{}' requesting the "
                        + "auto-detection, the default value ['{}'] will be applied", ZONE_PROPERTY_NAME, zone,
                        DEFAULT_ZONE);
            } else {
                logger.trace("The property [name : '{}' ] of zone can't be found, the default value ['{}'] will be applied",
                        ZONE_PROPERTY_NAME, DEFAULT_ZONE);
            }
            zone = DEFAULT_ZONE;
        }
        return zone;
    }
}
