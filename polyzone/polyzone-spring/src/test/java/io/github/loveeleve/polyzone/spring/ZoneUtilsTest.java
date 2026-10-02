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

import io.github.loveeleve.polyzone.ZoneContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link ZoneUtils}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ZoneUtilsTest {

    @Test
    void testGetZoneContext() {
        StaticApplicationContext context = new StaticApplicationContext();
        ZoneContext zoneContext = ZoneContext.get();
        context.getBeanFactory().registerSingleton(ZoneUtils.ZONE_CONTEXT_BEAN_NAME, zoneContext);

        ZoneContext result = ZoneUtils.getZoneContext(context.getBeanFactory());
        assertNotNull(result);
        assertSame(zoneContext, result);
    }

    @Test
    void testGetZoneLocator() {
        StaticApplicationContext context = new StaticApplicationContext();

        ZoneLocator locator = new DefaultZoneLocator();
        context.getBeanFactory().registerSingleton(ZoneUtils.ZONE_LOCATOR_BEAN_NAME, locator);

        ZoneLocator result = ZoneUtils.getZoneLocator(context.getBeanFactory());
        assertNotNull(result);
        assertSame(locator, result);
    }
}
