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
package io.github.loveeleve.polydata.transaction.validation;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.config.validation.AbstractConfigurationConfigValidator;
import io.github.loveeleve.polydata.config.validation.ConfigValidator;
import io.github.loveeleve.polydata.config.validation.ValidationErrors;
import org.springframework.boot.autoconfigure.transaction.PlatformTransactionManagerCustomizer;
import org.springframework.util.StringUtils;

/**
 * Dynamic JDBC {@link ConfigValidator} for {@link DynamicJdbcConfig.Transaction}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class TransactionConfigValidator extends AbstractConfigurationConfigValidator<DynamicJdbcConfig.Transaction> {

    @Override
    protected void validateName(DynamicJdbcConfig dynamicJdbcConfig, String name, ValidationErrors errors) {
        if (StringUtils.hasText(name)) {
            if (beanFactory.containsBeanDefinition(name)) {
                errors.addError("'{}' modules' bean name['{}'] is already registered in the BeanFactory[{}]", getModule(), name, beanFactory);
            }
        }
    }

    @Override
    protected void doValidate(DynamicJdbcConfig dynamicJdbcConfig, DynamicJdbcConfig.Transaction transaction, ValidationErrors errors) {
        validateCustomizers(dynamicJdbcConfig, transaction, errors);
    }

    private void validateCustomizers(DynamicJdbcConfig dynamicJdbcConfig, DynamicJdbcConfig.Transaction transaction, ValidationErrors errors) {
        validateComponentType(transaction.getCustomizers(), "customizers", PlatformTransactionManagerCustomizer.class, errors);
    }
}
