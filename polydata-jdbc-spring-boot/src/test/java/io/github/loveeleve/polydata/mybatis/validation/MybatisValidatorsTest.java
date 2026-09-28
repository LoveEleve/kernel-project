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
package io.github.loveeleve.polydata.mybatis.validation;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.config.validation.ValidationErrors;
import io.github.loveeleve.polydata.mybatisplus.validation.MybatisPlusConfigValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MybatisConfigValidator} and
 * {@link io.github.loveeleve.polydata.mybatisplus.validation.MybatisPlusConfigValidator} Test : the
 * "mybatis" and "mybatis-plus" modules are mutual exclusive
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class MybatisValidatorsTest {

    @Test
    void testMutualExclusion() {
        DynamicJdbcConfig dynamicJdbcConfig = new DynamicJdbcConfig();
        dynamicJdbcConfig.setName("test");
        dynamicJdbcConfig.setMybatis(new DynamicJdbcConfig.Mybatis());
        dynamicJdbcConfig.setMybatisPlus(new DynamicJdbcConfig.MybatisPlus());

        ValidationErrors mybatisErrors = new ValidationErrors("test");
        new MybatisConfigValidator().validate(dynamicJdbcConfig, "polydata.jdbc.configs.test", mybatisErrors);
        assertFalse(mybatisErrors.isValid());

        ValidationErrors mybatisPlusErrors = new ValidationErrors("test");
        new MybatisPlusConfigValidator().validate(dynamicJdbcConfig, "polydata.jdbc.configs.test", mybatisPlusErrors);
        assertFalse(mybatisPlusErrors.isValid());
    }

    @Test
    void testMybatisOnly() {
        DynamicJdbcConfig dynamicJdbcConfig = new DynamicJdbcConfig();
        dynamicJdbcConfig.setName("test");
        dynamicJdbcConfig.setMybatis(new DynamicJdbcConfig.Mybatis());

        ValidationErrors mybatisErrors = new ValidationErrors("test");
        new MybatisConfigValidator().validate(dynamicJdbcConfig, "polydata.jdbc.configs.test", mybatisErrors);
        assertTrue(mybatisErrors.isValid());
    }

    @Test
    void testMybatisPlusOnly() {
        DynamicJdbcConfig dynamicJdbcConfig = new DynamicJdbcConfig();
        dynamicJdbcConfig.setName("test");
        dynamicJdbcConfig.setMybatisPlus(new DynamicJdbcConfig.MybatisPlus());

        ValidationErrors mybatisPlusErrors = new ValidationErrors("test");
        new MybatisPlusConfigValidator().validate(dynamicJdbcConfig, "polydata.jdbc.configs.test", mybatisPlusErrors);
        assertTrue(mybatisPlusErrors.isValid());
    }
}
