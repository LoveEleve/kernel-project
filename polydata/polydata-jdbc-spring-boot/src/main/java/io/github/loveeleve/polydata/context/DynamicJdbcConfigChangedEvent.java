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
package io.github.loveeleve.polydata.context;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationContextEvent;

/**
 * {@link DynamicJdbcConfig} Changed {@link ApplicationEvent Event}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class DynamicJdbcConfigChangedEvent extends ApplicationContextEvent {

    private final DynamicJdbcConfig dynamicJdbcConfig;

    private final String propertyName;

    /**
     * Create a new ApplicationEvent.
     * 
     * @param context
     * @param dynamicJdbcConfig
     * @param propertyName
     */
    public DynamicJdbcConfigChangedEvent(ConfigurableApplicationContext context, DynamicJdbcConfig dynamicJdbcConfig, String propertyName) {
        super(context);
        this.dynamicJdbcConfig = dynamicJdbcConfig;
        this.propertyName = propertyName;
    }

    @Override
    public ConfigurableApplicationContext getSource() {
        return (ConfigurableApplicationContext) super.getSource();
    }

    public DynamicJdbcConfig getDynamicJdbcConfig() {
        return dynamicJdbcConfig;
    }

    public String getPropertyName() {
        return propertyName;
    }
}
