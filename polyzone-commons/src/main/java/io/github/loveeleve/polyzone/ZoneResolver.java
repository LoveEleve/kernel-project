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
package io.github.loveeleve.polyzone;

import java.util.function.Function;

/**
 * The zone resolver from the specified entity (a service instance, a Ribbon server, ...).
 *
 * @param <E> the type of entity to resolve zone from
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public interface ZoneResolver<E> extends Function<E, String> {

    @Override
    default String apply(E entity) {
        return resolve(entity);
    }

    /**
     * Resolve the zone
     *
     * @param entity the specified entity
     * @return null if can't be resolved
     */
    String resolve(E entity);
}
