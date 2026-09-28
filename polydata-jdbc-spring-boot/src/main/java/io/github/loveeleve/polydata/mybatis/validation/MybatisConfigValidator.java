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
import io.github.loveeleve.polydata.config.validation.AbstractConfigurationConfigValidator;
import io.github.loveeleve.polydata.config.validation.ConfigValidator;
import io.github.loveeleve.polydata.config.validation.ValidationErrors;

import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.MYBATIS_PLUS_MODULE;

/**
 * Dynamic JDBC {@link ConfigValidator} for {@link DynamicJdbcConfig.Mybatis}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class MybatisConfigValidator extends AbstractConfigurationConfigValidator<DynamicJdbcConfig.Mybatis> {

    @Override
    protected void doValidate(DynamicJdbcConfig dynamicJdbcConfig, DynamicJdbcConfig.Mybatis mybatis, ValidationErrors errors) {
        if (dynamicJdbcConfig.hasMybatisPlus()) {
            errors.addError("'{}' module and '{}' module must not be present at the same time", getModule(), MYBATIS_PLUS_MODULE);
        }
    }
}
