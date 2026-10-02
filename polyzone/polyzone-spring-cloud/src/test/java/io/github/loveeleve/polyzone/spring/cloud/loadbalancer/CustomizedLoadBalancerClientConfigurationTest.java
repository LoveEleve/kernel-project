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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the wiring of {@link CustomizedLoadBalancerClientConfiguration}: the optimized zone
 * preference supplier is created only when the LoadBalancer configuration is selected.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class CustomizedLoadBalancerClientConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CustomizedLoadBalancerClientConfiguration.class)
            .withBean(ZoneContext.class, ZoneContext::get)
            .withBean(DiscoveryClient.class, TestDiscoveryClient::new);

    @AfterEach
    void tearDown() {
        ZoneContext.get().reset();
    }

    @Test
    void testZonePreferenceSupplierIsCreatedForOptimizedConfiguration() {
        contextRunner
                .withPropertyValues("spring.cloud.loadbalancer.configurations=optimized-zone-preference")
                .run(context -> {
                    assertThat(context).hasSingleBean(ZonePreferenceFilter.class);
                    assertThat(context).hasSingleBean(ServiceInstanceListSupplier.class);
                    assertThat(context.getBean(ServiceInstanceListSupplier.class))
                            .isInstanceOf(ZonePreferenceServiceInstanceListSupplier.class);
                });
    }

    @Test
    void testNoZonePreferenceSupplierForOtherConfigurations() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ZonePreferenceFilter.class);
            assertThat(context).doesNotHaveBean(ServiceInstanceListSupplier.class);
        });
    }

    static class TestDiscoveryClient implements DiscoveryClient {

        @Override
        public String description() {
            return "Test Discovery Client";
        }

        @Override
        public List<ServiceInstance> getInstances(String serviceId) {
            return Collections.emptyList();
        }

        @Override
        public List<String> getServices() {
            return Collections.emptyList();
        }
    }
}
