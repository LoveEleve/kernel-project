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

import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.ConfigurablePropertyResolver;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.List;

/**
 * The utilities class for {@link Environment}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class EnvironmentUtils {

    private EnvironmentUtils() {
    }

    /**
     * Get the {@link ConversionService} from the given {@link Environment}.
     *
     * @param environment the {@link Environment}
     * @return non-null
     */
    public static ConversionService getConversionService(Environment environment) {
        if (environment instanceof ConfigurablePropertyResolver) {
            ConversionService conversionService = ((ConfigurablePropertyResolver) environment).getConversionService();
            if (conversionService != null) {
                return conversionService;
            }
        }
        return ApplicationConversionService.getSharedInstance();
    }

    /**
     * Resolve the placeholders of the given value and convert it to the target type.
     *
     * @param environment the {@link Environment}
     * @param value       the value
     * @param targetType  the target type
     * @param <T>         the target type
     * @return the resolved value, {@code null} if the value is {@code null} or can't be converted
     */
    public static <T> T resolvePlaceholders(Environment environment, String value, Class<T> targetType) {
        return resolvePlaceholders(environment, value, targetType, null);
    }

    /**
     * Resolve the placeholders of the given value and convert it to the target type.
     *
     * @param environment  the {@link Environment}
     * @param value        the value
     * @param targetType   the target type
     * @param defaultValue the default value
     * @param <T>          the target type
     * @return the resolved value, the default value if the value is {@code null} or can't be
     * converted
     */
    public static <T> T resolvePlaceholders(Environment environment, String value, Class<T> targetType, T defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String resolvedValue = environment.resolvePlaceholders(value);
        ConversionService conversionService = getConversionService(environment);
        if (conversionService.canConvert(String.class, targetType)) {
            return conversionService.convert(resolvedValue, targetType);
        }
        return defaultValue;
    }

    /**
     * Resolve the comma delimited value to a {@link List} of String.
     *
     * @param environment the {@link Environment}
     * @param value       the value
     * @return non-null, an empty list if the value is {@code null}
     */
    public static List<String> resolveCommaDelimitedValueToList(Environment environment, String value) {
        List<String> values = resolvePlaceholders(environment, value, List.class);
        return values == null ? Collections.emptyList() : values;
    }
}
