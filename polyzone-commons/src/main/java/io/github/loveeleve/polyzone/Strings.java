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

import java.util.ArrayList;
import java.util.List;

/**
 * Internal string utilities, so that the {@code polyzone-commons} module does not depend on any
 * framework.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
final class Strings {

    private Strings() {
    }

    /**
     * Whether the given value is {@code null}, empty or contains whitespace characters only.
     */
    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Whether the given value is not blank.
     */
    static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    /**
     * Remove all whitespace characters from the given value.
     */
    static String trimAllWhitespace(String value) {
        if (value == null) {
            return null;
        }
        int length = value.length();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char character = value.charAt(i);
            if (!Character.isWhitespace(character)) {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    /**
     * Split the given value by the delimiter character, always returning at least one element.
     */
    static String[] split(String value, char delimiter) {
        if (value == null) {
            return new String[0];
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        int length = value.length();
        for (int i = 0; i < length; i++) {
            if (value.charAt(i) == delimiter) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts.toArray(new String[0]);
    }

}
