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
package io.github.loveeleve.polydata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.readResourceContent;
import static java.util.Arrays.asList;

/**
 * Abstract TestCase
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class AbstractTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        objectMapper.registerModule(new JavaTimeModule());
    }

    protected static <T> T fromJsonResource(String resourceLocation, Class<T> valueType) {
        String jsonContent = getContent(resourceLocation);
        return fromJson(jsonContent, valueType);
    }

    protected static <T> T fromJson(String jsonContent, Class<T> valueType) {
        try {
            return objectMapper.readValue(jsonContent, valueType);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    protected static String getContent(String resourceLocation) {
        return readResourceContent(resourceLocation);
    }

    @SafeVarargs
    public static <T> Set<T> ofSet(T... values) {
        return new LinkedHashSet<>((List<T>) asList(values));
    }

    @SafeVarargs
    public static <T> T[] of(T... values) {
        return values;
    }

}
