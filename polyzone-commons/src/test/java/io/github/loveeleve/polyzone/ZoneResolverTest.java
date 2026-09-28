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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link ZoneResolver} default method.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class ZoneResolverTest {

    @Test
    void testApplyDelegatesToResolve() {
        ZoneResolver<String> resolver = entity -> entity + "-zone";
        // apply() is the default method that delegates to resolve()
        assertEquals("test-zone", resolver.apply("test"));
        assertEquals("test-zone", resolver.resolve("test"));
    }

    @Test
    void testApplyWithNullEntity() {
        ZoneResolver<String> resolver = entity -> entity;
        assertNull(resolver.apply(null));
    }
}
