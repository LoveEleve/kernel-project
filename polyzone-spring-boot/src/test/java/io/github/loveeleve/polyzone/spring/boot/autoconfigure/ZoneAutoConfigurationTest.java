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
package io.github.loveeleve.polyzone.spring.boot.autoconfigure;

import io.github.loveeleve.polyzone.ZoneContext;
import io.github.loveeleve.polyzone.spring.CompositeZoneLocator;
import io.github.loveeleve.polyzone.spring.ZoneLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE;
import static io.github.loveeleve.polyzone.ZoneConstants.ZONE_PROPERTY_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ZoneAutoConfiguration}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ZoneAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(ZoneAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        ZoneContext.get().reset();
    }

    @Test
    void testZoneContextAndZoneLocatorBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ZoneContext.class);
            assertThat(context).hasBean("zoneContext");
            assertThat(context).hasBean("zoneLocator");
            assertThat(context).hasSingleBean(CompositeZoneLocator.class);
            assertThat(context).hasSingleBean(io.github.loveeleve.polyzone.spring.event.ZoneContextChangedListener.class);
        });
    }

    @Test
    void testZoneLocatedFromProperty() {
        contextRunner.withPropertyValues(ZONE_PROPERTY_NAME + "=zone-a").run(context -> {
            ZoneLocator zoneLocator = context.getBean("zoneLocator", ZoneLocator.class);
            assertThat(zoneLocator.locate(context.getEnvironment())).isEqualTo("zone-a");
        });
    }

    @Test
    void testZoneLocatedFromDefaultLocator() {
        contextRunner.run(context -> {
            ZoneLocator zoneLocator = context.getBean("zoneLocator", ZoneLocator.class);
            assertThat(zoneLocator.locate(context.getEnvironment())).isEqualTo(DEFAULT_ZONE);
        });
    }

    @Test
    void testZoneFeatureDisabled() {
        contextRunner.withPropertyValues("polyzone.zone.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ZoneContext.class);
            assertThat(context).doesNotHaveBean("zoneLocator");
        });
    }

    @Test
    void testApplicationStartedEventChangesZoneContext() {
        contextRunner.withPropertyValues(ZONE_PROPERTY_NAME + "=zone-b").run(context -> {
            context.publishEvent(new ApplicationStartedEvent(new SpringApplication(), new String[0], context,
                    Duration.ZERO));
            assertThat(ZoneContext.get().getZone()).isEqualTo("zone-b");
        });
    }

    @Test
    void testFactoryLoadedZoneLocatorsReceiveLifecycleCallbacks() {
        contextRunner.run(context ->
                assertThat(TestEnvironmentAwareZoneLocator.environment).isSameAs(context.getEnvironment()));
    }
}
