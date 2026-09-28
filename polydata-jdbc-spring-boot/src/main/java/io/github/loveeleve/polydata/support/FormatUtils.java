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
 * The utilities class for text formatting with the {@code {}} placeholder, e.g.
 * <pre>
 * format("Hello, {}", "World") returns "Hello, World"
 * format("{} + {} = {}", 1, 2, 3) returns "1 + 2 = 3"
 * </pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class FormatUtils {

    private static final String PLACEHOLDER = "{}";

    private static final int PLACEHOLDER_LENGTH = PLACEHOLDER.length();

    private FormatUtils() {
    }

    /**
     * Replace each {@code {}} placeholder of the pattern with the {@link String} representation of
     * the argument at the same position.
     *
     * <p>
     * When there are more placeholders than arguments, the extra placeholders remain unchanged.
     *
     * @param pattern the pattern
     * @param args    the arguments
     * @return the formatted text, the original pattern if it is blank or no argument is given
     */
    public static String format(String pattern, Object... args) {
        if (pattern == null || pattern.isEmpty() || args == null || args.length == 0) {
            return pattern;
        }

        StringBuilder builder = new StringBuilder(pattern.length() + args.length * 16);

        int argsLength = args.length;

        int argsIndex = 0;

        int startIndex = 0;

        int index = pattern.indexOf(PLACEHOLDER);

        while (index > -1 && argsIndex < argsLength) {
            builder.append(pattern, startIndex, index);
            builder.append(String.valueOf(args[argsIndex++]));
            startIndex = index + PLACEHOLDER_LENGTH;
            index = pattern.indexOf(PLACEHOLDER, startIndex);
        }

        if (startIndex < pattern.length()) {
            builder.append(pattern, startIndex, pattern.length());
        }

        return builder.toString();
    }
}
