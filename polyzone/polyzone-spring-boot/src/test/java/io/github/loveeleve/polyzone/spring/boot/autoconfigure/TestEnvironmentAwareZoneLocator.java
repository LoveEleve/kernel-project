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

import io.github.loveeleve.polyzone.spring.AbstractZoneLocator;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * A test {@link io.github.loveeleve.polyzone.spring.ZoneLocator} registered in
 * {@code META-INF/spring.factories} to verify that factory-loaded locators receive the Spring
 * lifecycle callbacks.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class TestEnvironmentAwareZoneLocator extends AbstractZoneLocator implements EnvironmentAware {

    static volatile Environment environment;

    public TestEnvironmentAwareZoneLocator() {
        super(100);
    }

    @Override
    public boolean supports(Environment environment) {
        return false;
    }

    @Override
    public String locate(Environment environment) {
        return null;
    }

    @Override
    public void setEnvironment(Environment environment) {
        TestEnvironmentAwareZoneLocator.environment = environment;
    }
}
