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
import io.github.loveeleve.polyzone.spring.cloud.event.ZoneAttachmentListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ZoneCloudAutoConfiguration}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ZoneCloudAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ZoneAutoConfiguration.class, ZoneCloudAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        ZoneContext.get().reset();
    }

    @Test
    void testZoneAttachmentBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ZoneAttachmentHandler.class);
            assertThat(context).hasSingleBean(ZoneAttachmentListener.class);
        });
    }

    @Test
    void testZoneFeatureDisabled() {
        contextRunner.withPropertyValues("polyzone.zone.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ZoneContext.class);
            assertThat(context).doesNotHaveBean(ZoneAttachmentListener.class);
        });
    }

    @Test
    void testAutoRegistrationDisabled() {
        contextRunner.withPropertyValues("spring.cloud.service-registry.auto-registration.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ZoneAttachmentListener.class));
    }
}
