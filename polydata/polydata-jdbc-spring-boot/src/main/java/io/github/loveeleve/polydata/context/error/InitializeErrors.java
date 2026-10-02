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
package io.github.loveeleve.polydata.context.error;

import org.springframework.util.CollectionUtils;

import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.loveeleve.polydata.support.FormatUtils.format;

public class InitializeErrors {

    private final Map<String, Throwable> errors;

    public InitializeErrors() {
        errors = new ConcurrentHashMap<>();
    }

    public void addError(String dynamicJdbcPropertyName, Throwable error) {
        errors.putIfAbsent(dynamicJdbcPropertyName, error);
    }

    public boolean hasError() {
        return !CollectionUtils.isEmpty(errors);
    }

    @Override
    public String toString() {
        if (!hasError()) {
            return "No Initialize Error";
        }
        String formatMessage = "DynamicJdbcConfig[name : {}] Initialize Error : [{}: {}]";
        StringJoiner messageBuilder = new StringJoiner(System.lineSeparator());
        errors.forEach((key, value) -> messageBuilder.add(format(formatMessage, key, value.getClass().getSimpleName(), value.getMessage())));
        return messageBuilder.toString();
    }
}
