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

/**
 * The utilities class for {@link String} with the Apache Commons Lang semantics.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class StringUtils {

    private static final String EMPTY = "";

    private StringUtils() {
    }

    /**
     * Gets the substring before the first occurrence of the separator.
     *
     * @param str       the String to search
     * @param separator the String to search for
     * @return {@code null} if {@code str} is {@code null}, {@code str} if the separator is
     * {@code null} or not found, an empty String if the separator is empty
     */
    public static String substringBefore(String str, String separator) {
        if (isEmpty(str) || separator == null) {
            return str;
        }
        if (separator.isEmpty()) {
            return EMPTY;
        }
        int pos = str.indexOf(separator);
        if (pos == -1) {
            return str;
        }
        return str.substring(0, pos);
    }

    /**
     * Gets the substring after the first occurrence of the separator.
     *
     * @param str       the String to search
     * @param separator the String to search for
     * @return {@code null} if {@code str} is {@code null}, {@code str} if {@code str} is empty, an
     * empty String if the separator is {@code null} or not found, {@code str} if the separator is
     * empty
     */
    public static String substringAfter(String str, String separator) {
        if (isEmpty(str)) {
            return str;
        }
        if (separator == null) {
            return EMPTY;
        }
        int pos = str.indexOf(separator);
        if (pos == -1) {
            return EMPTY;
        }
        return str.substring(pos + separator.length());
    }

    /**
     * Tests if the value starts with the given part.
     *
     * @param value the value
     * @param part  the part
     * @return {@code false} if either argument is {@code null}
     */
    public static boolean startsWith(String value, String part) {
        if (value == null || part == null) {
            return false;
        }
        return value.startsWith(part);
    }

    /**
     * Tests if the String is {@code null} or has zero length.
     *
     * @param str the String
     * @return {@code true} if empty
     */
    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    /**
     * Tests if the String is not {@code null} and has non-zero length.
     *
     * @param str the String
     * @return {@code true} if not empty
     */
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }
}
