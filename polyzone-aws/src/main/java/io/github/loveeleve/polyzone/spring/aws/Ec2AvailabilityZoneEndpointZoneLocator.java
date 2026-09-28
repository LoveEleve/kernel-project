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
package io.github.loveeleve.polyzone.spring.aws;

import io.github.loveeleve.polyzone.HttpUtils;
import io.github.loveeleve.polyzone.spring.AbstractZoneLocator;
import io.github.loveeleve.polyzone.spring.ZoneLocator;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_TIMEOUT;
import static io.github.loveeleve.polyzone.ZoneConstants.LOCATOR_TIMEOUT_PROPERTY_NAME;

/**
 * Amazon EC2 Availability Zone Endpoint {@link ZoneLocator}.
 *
 * <p>
 * It locates the zone from the EC2 instance metadata endpoint, whose URI can be customized with the
 * property {@value #AVAILABILITY_ZONE_ENDPOINT_URI_PROPERTY_NAME} (defaults to
 * {@value #DEFAULT_AVAILABILITY_ZONE_ENDPOINT_URI}).
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ZoneLocator
 * @since 1.0.0
 */
public class Ec2AvailabilityZoneEndpointZoneLocator extends AbstractZoneLocator implements EnvironmentAware {

    public static final String AVAILABILITY_ZONE_ENDPOINT_URI_PROPERTY_NAME = "EC2_AVAILABILITY_ZONE_ENDPOINT_URI";

    public static final String DEFAULT_AVAILABILITY_ZONE_ENDPOINT_URI =
            "http://169.254.169.254/latest/meta-data/placement/availability-zone";

    public static final int DEFAULT_ORDER = 15;

    private int timeout = DEFAULT_TIMEOUT;

    public Ec2AvailabilityZoneEndpointZoneLocator() {
        super(DEFAULT_ORDER);
    }

    @Override
    public boolean supports(Environment environment) {
        return true;
    }

    @Override
    public String locate(Environment environment) {
        String uri = getAvailabilityZoneEndpointURI(environment);
        String zone = null;
        if (StringUtils.hasText(uri)) {
            try {
                zone = HttpUtils.doGet(uri, timeout);
                logger.info("The zone ['{}'] was located from the EC2 Availability Zone Endpoint[URI : '{}' , property name: '{}']",
                        zone, uri, AVAILABILITY_ZONE_ENDPOINT_URI_PROPERTY_NAME);
            } catch (Throwable e) {
                logger.error("Request Amazon EC2 Availability Zone Endpoint[URI : '{}'] failed", uri, e);
            }
        } else {
            logger.warn("The property[name : '{}'] of Amazon EC2 Availability Zone Endpoint can't be found",
                    AVAILABILITY_ZONE_ENDPOINT_URI_PROPERTY_NAME);
        }
        return zone;
    }

    private String getAvailabilityZoneEndpointURI(Environment environment) {
        return environment.getProperty(AVAILABILITY_ZONE_ENDPOINT_URI_PROPERTY_NAME,
                DEFAULT_AVAILABILITY_ZONE_ENDPOINT_URI);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.timeout = environment.getProperty(LOCATOR_TIMEOUT_PROPERTY_NAME, int.class, DEFAULT_TIMEOUT);
    }
}
