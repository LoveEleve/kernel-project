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

import org.springframework.core.env.Environment;

/**
 * The locator for the zone where the current application is deployed.
 *
 * <p>
 * Implementations are discovered from Spring beans and from {@code META-INF/spring.factories},
 * then composed by {@link CompositeZoneLocator} in {@link org.springframework.core.Ordered order}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public interface ZoneLocator {

    /**
     * Does the current {@link ZoneLocator} support the given environment?
     *
     * @param environment {@link Environment}
     * @return if {@code true}, {@link #locate(Environment)} will be invoked, or the invocation will
     * be ignored.
     */
    boolean supports(Environment environment);

    /**
     * Locate the zone.
     *
     * @param environment {@link Environment}
     * @return the zone info, or {@code null} if it can't be located
     */
    String locate(Environment environment);

}
