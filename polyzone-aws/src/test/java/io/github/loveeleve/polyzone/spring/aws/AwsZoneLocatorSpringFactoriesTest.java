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

import io.github.loveeleve.polyzone.spring.ZoneLocator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the AWS {@link ZoneLocator ZoneLocators} are contributed through
 * {@code META-INF/spring.factories}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class AwsZoneLocatorSpringFactoriesTest {

    @Test
    void testAwsZoneLocatorsAreRegistered() {
        List<ZoneLocator> zoneLocators = SpringFactoriesLoader
                .forDefaultResourceLocation(getClass().getClassLoader())
                .load(ZoneLocator.class);

        assertTrue(zoneLocators.stream().anyMatch(locator -> locator instanceof EcsContainerMetadataFileZoneLocator));
        assertTrue(zoneLocators.stream().anyMatch(locator -> locator instanceof EcsTaskMetadataEndpointV4ZoneLocator));
        assertTrue(zoneLocators.stream().anyMatch(locator -> locator instanceof Ec2AvailabilityZoneEndpointZoneLocator));
    }
}
