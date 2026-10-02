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

import io.github.loveeleve.polyzone.ZonePreferenceFilter;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * The zone preference aware {@link ServiceInstanceListSupplier}, an optimized replacement of the
 * Spring Cloud built-in
 * {@code org.springframework.cloud.loadbalancer.core.ZonePreferenceServiceInstanceListSupplier}.
 *
 * <p>
 * Compared with the built-in supplier, it adds the zone discovery (through
 * {@link io.github.loveeleve.polyzone.spring.ZoneLocator}), two safety thresholds and the support
 * of disabled zones.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ZonePreferenceFilter
 * @since 1.0.0
 */
public class ZonePreferenceServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private final ZonePreferenceFilter<ServiceInstance> zonePreferenceFilter;

    public ZonePreferenceServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
                                                     ZonePreferenceFilter<ServiceInstance> zonePreferenceFilter) {
        super(delegate);
        this.zonePreferenceFilter = zonePreferenceFilter;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return getDelegate().get().map(this::filteredByZone);
    }

    private List<ServiceInstance> filteredByZone(List<ServiceInstance> serviceInstances) {
        return zonePreferenceFilter.filter(serviceInstances);
    }
}
