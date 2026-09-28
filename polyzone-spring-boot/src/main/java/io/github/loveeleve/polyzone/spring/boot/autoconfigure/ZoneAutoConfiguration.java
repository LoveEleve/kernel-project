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
import io.github.loveeleve.polyzone.spring.boot.condition.ConditionalOnZoneAvailable;
import io.github.loveeleve.polyzone.spring.event.ZoneContextChangedListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static io.github.loveeleve.polyzone.ZoneContext.get;
import static io.github.loveeleve.polyzone.spring.ZoneUtils.ZONE_CONTEXT_BEAN_NAME;
import static io.github.loveeleve.polyzone.spring.ZoneUtils.ZONE_LOCATOR_BEAN_NAME;

/**
 * Zone Auto-Configuration.
 *
 * <p>
 * It exposes the singleton {@link ZoneContext} as a bean and builds the
 * {@link CompositeZoneLocator} from both {@link ZoneLocator} Spring beans and
 * {@code META-INF/spring.factories} declarations (e.g. the ones contributed by
 * {@code polyzone-aws}).
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnZoneAvailable
@Import(value = {ZoneContextChangedListener.class})
public class ZoneAutoConfiguration {

    @Bean(name = ZONE_CONTEXT_BEAN_NAME)
    @ConditionalOnMissingBean
    public ZoneContext zoneContext() {
        return get();
    }

    @Primary
    @Bean(name = ZONE_LOCATOR_BEAN_NAME)
    @ConditionalOnMissingBean
    public CompositeZoneLocator zoneLocator(Collection<ZoneLocator> zoneLocatorBeans,
                                            ConfigurableApplicationContext context) {
        // Load from Spring Factories
        List<ZoneLocator> zoneLocators =
                SpringFactoriesLoader.forDefaultResourceLocation(context.getClassLoader()).load(ZoneLocator.class);
        List<ZoneLocator> allZoneLocators = new ArrayList<>(zoneLocatorBeans.size() + zoneLocators.size());
        // Add ZoneLocator Spring Beans
        allZoneLocators.addAll(zoneLocatorBeans);
        // Add ZoneLocator Spring Factories
        allZoneLocators.addAll(zoneLocators);
        // Sort by the order
        AnnotationAwareOrderComparator.sort(allZoneLocators);
        return new CompositeZoneLocator(allZoneLocators);
    }

}
