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
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * The utilities class for Zone.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class ZoneUtils {

    /**
     * {@link ZoneContext} Bean Name
     */
    public static final String ZONE_CONTEXT_BEAN_NAME = "zoneContext";

    /**
     * {@link ZoneLocator} Bean Name
     */
    public static final String ZONE_LOCATOR_BEAN_NAME = "zoneLocator";

    private ZoneUtils() {
    }

    public static ZoneContext getZoneContext(ConfigurableListableBeanFactory beanFactory) {
        return beanFactory.getBean(ZONE_CONTEXT_BEAN_NAME, ZoneContext.class);
    }

    public static ZoneLocator getZoneLocator(ConfigurableListableBeanFactory beanFactory) {
        return beanFactory.getBean(ZONE_LOCATOR_BEAN_NAME, ZoneLocator.class);
    }

}
