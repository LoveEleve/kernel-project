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

import org.springframework.util.ClassUtils;

/**
 * The utilities class for {@link ClassLoader}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class ClassLoaderUtils {

    private ClassLoaderUtils() {
    }

    /**
     * Determine whether the {@link Class} identified by the supplied name is present in the given
     * {@link ClassLoader}.
     *
     * @param className   the name of the class to check
     * @param classLoader the ClassLoader, default ClassLoader when {@code null}
     * @return {@code true} if present
     */
    public static boolean isPresent(String className, ClassLoader classLoader) {
        return ClassUtils.isPresent(className, classLoader);
    }

    /**
     * Determine whether the {@link Class} identified by the supplied name is present in the default
     * {@link ClassLoader}.
     *
     * @param className the name of the class to check
     * @return {@code true} if present
     */
    public static boolean isPresent(String className) {
        return isPresent(className, null);
    }
}
