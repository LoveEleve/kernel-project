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

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DynamicJdbcConfigValidator} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class DynamicJdbcConfigValidatorTest {

    private DynamicJdbcConfigValidator validator;

    private ValidationErrors errors;

    @BeforeEach
    void before() {
        validator = new DynamicJdbcConfigValidator();
        errors = new ValidationErrors("test");
    }

    @Test
    void testName() {
        DynamicJdbcConfig config = new DynamicJdbcConfig();
        validator.validate(config, "test", errors);
        assertFalse(errors.isValid());
    }

    @Test
    void testModules() {
        DynamicJdbcConfig config = new DynamicJdbcConfig();
        config.setName("test");
        validator.validate(config, "test", errors);
        assertFalse(errors.isValid());
    }

    @Test
    void testValid() {
        DynamicJdbcConfig config = new DynamicJdbcConfig();
        config.setName("test");
        config.setTransaction(new DynamicJdbcConfig.Transaction());
        validator.validate(config, "test", errors);
        assertTrue(errors.isValid());
    }
}
