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

import org.springframework.core.GenericTypeResolver;

/**
 * The utilities class for {@link java.lang.reflect.Type Type}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class TypeUtils {

    private TypeUtils() {
    }

    /**
     * Resolve the actual type argument of the given generic interface from the specified class.
     *
     * <p>
     * When the type argument can't be resolved from the class itself, the generic superclass is
     * used as the fallback.
     *
     * @param clazz            the class
     * @param genericInterface the generic interface
     * @param index            the index of the type argument (reserved, only index 0 is used)
     * @return the resolved type argument class, or {@code null} if it can't be resolved
     */
    @SuppressWarnings("unused")
    public static Class<?> resolveActualTypeArgumentClass(Class<?> clazz, Class<?> genericInterface, int index) {
        Class<?> typeArgumentClass = GenericTypeResolver.resolveTypeArgument(clazz, genericInterface);
        if (typeArgumentClass == null || typeArgumentClass == Object.class) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                Class<?> superTypeArgumentClass = GenericTypeResolver.resolveTypeArgument(superClass, genericInterface);
                if (superTypeArgumentClass != null && superTypeArgumentClass != Object.class) {
                    typeArgumentClass = superTypeArgumentClass;
                }
            }
        }
        return typeArgumentClass;
    }
}
