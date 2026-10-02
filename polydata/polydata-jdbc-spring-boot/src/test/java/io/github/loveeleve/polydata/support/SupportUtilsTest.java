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
package io.github.loveeleve.polydata.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.loveeleve.polydata.support.ClassLoaderUtils.isPresent;
import static io.github.loveeleve.polydata.support.EnvironmentUtils.resolveCommaDelimitedValueToList;
import static io.github.loveeleve.polydata.support.EnvironmentUtils.resolvePlaceholders;
import static io.github.loveeleve.polydata.support.FormatUtils.format;
import static io.github.loveeleve.polydata.support.PropertySourcesUtils.normalizePrefix;
import static io.github.loveeleve.polydata.support.StringUtils.startsWith;
import static io.github.loveeleve.polydata.support.StringUtils.substringAfter;
import static io.github.loveeleve.polydata.support.StringUtils.substringBefore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FormatUtils}, {@link StringUtils} and {@link ClassLoaderUtils} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class SupportUtilsTest {

    @Test
    void testFormat() {
        assertEquals("Hello, World", format("Hello, {}", "World"));
        assertEquals("1 + 2 = 3", format("{} + {} = {}", 1, 2, 3));
        assertEquals("a-b-c", format("{}-{}-{}", "a", "b", "c"));
        // More placeholders than arguments, the extra placeholders remain unchanged
        assertEquals("a-{}-{}", format("{}-{}-{}", "a"));
        // More arguments than placeholders
        assertEquals("a-b", format("{}-{}", "a", "b", "c"));
        // No argument or blank pattern returns the pattern
        assertEquals("Hello, {}", format("Hello, {}"));
        assertEquals("", format(""));
        assertNull(format(null, "a"));
        assertEquals("null", format("{}", (Object) null));
    }

    @Test
    void testSubstringBefore() {
        assertEquals("abc", substringBefore("abc:def", ":"));
        assertEquals("abc", substringBefore("abc", ":"));
        assertEquals("", substringBefore("abc", ""));
        assertEquals("abc:def", substringBefore("abc:def", null));
        assertNull(substringBefore(null, ":"));
    }

    @Test
    void testSubstringAfter() {
        assertEquals("def", substringAfter("abc:def", ":"));
        assertEquals("", substringAfter("abc", ":"));
        assertEquals("abc", substringAfter("abc", ""));
        assertEquals("", substringAfter("abc:def", null));
        assertNull(substringAfter(null, ":"));
    }

    @Test
    void testStartsWith() {
        assertTrue(startsWith("polydata.jdbc", "polydata."));
        assertFalse(startsWith("polydata.jdbc", "jdbc"));
        assertFalse(startsWith(null, "jdbc"));
        assertFalse(startsWith("polydata.jdbc", null));
    }

    @Test
    void testIsPresent() {
        assertTrue(isPresent("java.lang.String"));
        assertTrue(isPresent("java.lang.String", SupportUtilsTest.class.getClassLoader()));
        assertFalse(isPresent("io.github.loveeleve.polydata.NotExistClass"));
    }

    @Test
    void testNormalizePrefix() {
        assertEquals("polydata.jdbc.", normalizePrefix("polydata.jdbc"));
        assertEquals("polydata.jdbc.", normalizePrefix("polydata.jdbc."));
    }

    @Test
    void testResolvePlaceholdersAndCommaDelimitedValueToList() {
        org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment();
        environment.setProperty("app.name", "polydata");

        assertEquals("polydata-jdbc", resolvePlaceholders(environment, "${app.name}-jdbc", String.class));
        assertNull(resolvePlaceholders(environment, null, String.class));
        assertEquals("default", resolvePlaceholders(environment, null, String.class, "default"));

        List<String> values = resolveCommaDelimitedValueToList(environment, "a, b ,c");
        assertEquals(3, values.size());
        assertEquals("a", values.get(0));
        assertEquals("b", values.get(1));
        assertEquals("c", values.get(2));
        assertTrue(resolveCommaDelimitedValueToList(environment, null).isEmpty());
    }
}
