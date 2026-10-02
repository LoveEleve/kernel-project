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
package io.github.loveeleve.polydata.config.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.StringJoiner;

import static io.github.loveeleve.polydata.support.FormatUtils.format;


/**
 * Validation Errors encapsulation
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class ValidationErrors {

    private static final Logger logger  = LoggerFactory.getLogger(ValidationErrors.class);

    private final List<String> errorMessages = new LinkedList<>();

    private final String dynamicJdbcConfigName;

    public ValidationErrors(String dynamicJdbcConfigName) {
        this.dynamicJdbcConfigName = dynamicJdbcConfigName;
    }

    public ValidationErrors addError(String messagePattern, Object... args) {
        String errorMessage = format(messagePattern, args);
        errorMessages.add(errorMessage);
        logger.debug(errorMessage);
        return this;
    }

    public boolean isValid() {
        return errorMessages.isEmpty();
    }

    @Override
    public String toString() {
        if (isValid()) {
            return "No Validation Error";
        }

        String prefix = format("DynamicJdbcConfig[name : {}] Validation Errors : [", dynamicJdbcConfigName);
        String suffix = "]";

        StringJoiner messageBuilder = new StringJoiner(System.lineSeparator(), prefix, suffix);
        errorMessages.forEach(messageBuilder::add);
        return messageBuilder.toString();
    }
}
