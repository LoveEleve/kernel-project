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
package io.github.loveeleve.polyzone.spring.boot.condition;

import io.github.loveeleve.polyzone.ZoneContext;
import io.github.loveeleve.polyzone.spring.ZoneLocator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * {@link Conditional} that checks if the zone feature is available: the required classes are present
 * on the classpath and the zone feature is enabled.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see Conditional
 * @since 1.0.0
 */
@Retention(RUNTIME)
@Target({TYPE, METHOD})
@Documented
@ConditionalOnClass(value = {
        ZoneContext.class,                  // PolyZone Commons
        ZoneLocator.class                   // PolyZone Spring
})
@ConditionalOnZoneEnabled
public @interface ConditionalOnZoneAvailable {
}
