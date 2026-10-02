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
package io.github.loveeleve.polydata.datasource.validation;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.config.validation.ValidationErrors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.parseDynamicJdbcConfig;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataSourcePropertiesModuleValidator} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class DataSourcePropertiesModuleValidatorTest {

    private DataSourcePropertiesModuleValidator validator;

    private ValidationErrors errors;

    @BeforeEach
    void before() {
        validator = new DataSourcePropertiesModuleValidator();
        errors = new ValidationErrors("test");
    }

    @Test
    void testNoDataSourceModule() {
        DynamicJdbcConfig config = new DynamicJdbcConfig();
        config.setName("test");

        validator.validate(config, "polydata.jdbc.configs.test", errors);

        assertFalse(errors.isValid());
    }

    @Test
    void testDataSourceAndHighAvailabilityDataSourcePresent() {
        DynamicJdbcConfig config = parseDynamicJdbcConfig("test", "{\"name\":\"test\",\"datasource\":[{\"name\":\"ds\"}],"
                + "\"ha-datasource\":{\"defaultZone\":[{\"name\":\"ds\"}]}}");

        validator.validate(config, "polydata.jdbc.configs.test", errors);

        assertFalse(errors.isValid());
    }

    @Test
    void testValidDataSource() {
        DynamicJdbcConfig config = parseDynamicJdbcConfig("test", "{\"name\":\"test\",\"datasource\":["
                + "{\"name\":\"ds\",\"type\":\"com.zaxxer.hikari.HikariDataSource\","
                + "\"driverClassName\":\"org.h2.Driver\","
                + "\"url\":\"jdbc:h2:mem:test\",\"username\":\"sa\",\"password\":\"sa\"}]}");

        validator.validate(config, "polydata.jdbc.configs.test", errors);

        assertTrue(errors.isValid(), errors.toString());
    }

    @Test
    void testDuplicatedDataSourceNames() {
        DynamicJdbcConfig config = parseDynamicJdbcConfig("test", "{\"name\":\"test\",\"datasource\":["
                + "{\"name\":\"ds\",\"type\":\"com.zaxxer.hikari.HikariDataSource\",\"driverClassName\":\"org.h2.Driver\","
                + "\"url\":\"jdbc:h2:mem:test\",\"username\":\"sa\",\"password\":\"sa\"},"
                + "{\"name\":\"ds\",\"type\":\"com.zaxxer.hikari.HikariDataSource\",\"driverClassName\":\"org.h2.Driver\","
                + "\"url\":\"jdbc:h2:mem:test\",\"username\":\"sa\",\"password\":\"sa\"}]}");

        validator.validate(config, "polydata.jdbc.configs.test", errors);

        assertFalse(errors.isValid());
    }

    @Test
    void testHighAvailabilityDataSourceWithOneZone() {
        DynamicJdbcConfig config = parseDynamicJdbcConfig("test", "{\"name\":\"test\",\"ha-datasource\":{"
                + "\"defaultZone\":[{\"name\":\"ds\",\"type\":\"com.zaxxer.hikari.HikariDataSource\","
                + "\"driverClassName\":\"org.h2.Driver\",\"url\":\"jdbc:h2:mem:test\",\"username\":\"sa\",\"password\":\"sa\"}]}}");

        validator.validate(config, "polydata.jdbc.configs.test", errors);

        assertFalse(errors.isValid());
    }

    @Test
    void testValidHighAvailabilityDataSource() {
        DynamicJdbcConfig config = parseDynamicJdbcConfig("test", "{\"name\":\"test\",\"ha-datasource\":{"
                + "\"defaultZone\":[{\"name\":\"ds\",\"type\":\"com.zaxxer.hikari.HikariDataSource\","
                + "\"driverClassName\":\"org.h2.Driver\",\"url\":\"jdbc:h2:mem:test\",\"username\":\"sa\",\"password\":\"sa\"}],"
                + "\"test-zone\":[{\"name\":\"ds-0\",\"type\":\"com.zaxxer.hikari.HikariDataSource\","
                + "\"driverClassName\":\"org.h2.Driver\",\"url\":\"jdbc:h2:mem:test0\",\"username\":\"sa\",\"password\":\"sa\"}]}}");

        validator.validate(config, "polydata.jdbc.configs.test", errors);

        assertTrue(errors.isValid(), errors.toString());
    }
}
