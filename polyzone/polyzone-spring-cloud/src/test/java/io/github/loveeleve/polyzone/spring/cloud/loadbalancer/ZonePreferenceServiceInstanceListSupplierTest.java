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
package io.github.loveeleve.polyzone.spring.cloud.loadbalancer;

import io.github.loveeleve.polyzone.ZoneContext;
import io.github.loveeleve.polyzone.ZonePreferenceFilter;
import io.github.loveeleve.polyzone.spring.cloud.CloudServerZoneResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.github.loveeleve.polyzone.ZoneConstants.ZONE_PROPERTY_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ZonePreferenceServiceInstanceListSupplier}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ZonePreferenceServiceInstanceListSupplierTest {

    private ZoneContext zoneContext;

    private ZonePreferenceServiceInstanceListSupplier supplier;

    @BeforeEach
    void setUp() {
        zoneContext = ZoneContext.get();
        zoneContext.reset();
        zoneContext.setEnabled(true);
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setZone("zone-a");
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(1);
        ZonePreferenceFilter<ServiceInstance> zonePreferenceFilter =
                new ZonePreferenceFilter<>(zoneContext, new CloudServerZoneResolver());
        supplier = new ZonePreferenceServiceInstanceListSupplier(new FixedServiceInstanceListSupplier(), zonePreferenceFilter);
    }

    @AfterEach
    void tearDown() {
        zoneContext.reset();
    }

    @Test
    void testFilteredBySameZone() {
        List<ServiceInstance> instances = supplier.get().blockFirst();
        assertEquals(2, instances.size());
        instances.forEach(instance -> assertEquals("zone-a", instance.getMetadata().get(ZONE_PROPERTY_NAME)));
    }

    @Test
    void testPreferenceDisabledReturnsAllInstances() {
        zoneContext.setPreferenceEnabled(false);
        List<ServiceInstance> instances = supplier.get().blockFirst();
        assertEquals(4, instances.size());
    }

    @Test
    void testDisabledZoneIsExcluded() {
        zoneContext.setPreferenceUpstreamDisabledZone("zone-c");
        List<ServiceInstance> instances = supplier.get().blockFirst();
        assertEquals(2, instances.size());
        instances.forEach(instance -> assertEquals("zone-a", instance.getMetadata().get(ZONE_PROPERTY_NAME)));
    }

    static class FixedServiceInstanceListSupplier implements ServiceInstanceListSupplier {

        @Override
        public String getServiceId() {
            return "test-service";
        }

        @Override
        public Flux<List<ServiceInstance>> get() {
            return Flux.just(Arrays.asList(
                    newInstance("zone-a", 1),
                    newInstance("zone-a", 2),
                    newInstance("zone-b", 3),
                    newInstance("zone-c", 4)
            ));
        }

        private static ServiceInstance newInstance(String zone, int index) {
            return new DefaultServiceInstance("instance-" + index, "test-service", "host-" + index, 8080, false,
                    Map.of(ZONE_PROPERTY_NAME, zone));
        }
    }
}
