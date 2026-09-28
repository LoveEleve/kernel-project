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
package io.github.loveeleve.polyconfig.spring.boot.autoconfigure;

import io.github.loveeleve.polyconfig.spring.event.ConfigChangePublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * PolyConfig Auto-Configuration.
 *
 * <p>
 * It exposes the {@link ConfigChangePublisher} bean and, when Spring Cloud Context is present, the
 * {@link EnvironmentConfigChangeListener} that translates {@code EnvironmentChangeEvent} into the
 * unified PolyConfig event.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@AutoConfiguration
public class PolyConfigAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConfigChangePublisher configChangePublisher(ApplicationEventPublisher eventPublisher) {
        return new ConfigChangePublisher(eventPublisher);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.cloud.context.environment.EnvironmentChangeEvent")
    static class EnvironmentChangeConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public EnvironmentConfigChangeListener environmentConfigChangeListener(ConfigChangePublisher changePublisher,
                                                                               Environment environment) {
            return new EnvironmentConfigChangeListener(changePublisher, environment);
        }
    }
}
