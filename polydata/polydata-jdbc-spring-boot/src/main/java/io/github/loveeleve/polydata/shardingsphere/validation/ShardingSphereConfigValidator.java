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
package io.github.loveeleve.polydata.shardingsphere.validation;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.config.validation.AbstractConfigurationConfigValidator;
import io.github.loveeleve.polydata.config.validation.ConfigValidator;
import io.github.loveeleve.polydata.config.validation.ValidationErrors;
import org.springframework.core.io.Resource;

import static org.springframework.util.StringUtils.hasText;

/**
 * Dynamic JDBC {@link ConfigValidator} for {@link DynamicJdbcConfig.ShardingSphere}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class ShardingSphereConfigValidator extends AbstractConfigurationConfigValidator<DynamicJdbcConfig.ShardingSphere> {

    @Override
    protected void doValidate(DynamicJdbcConfig dynamicJdbcConfig, DynamicJdbcConfig.ShardingSphere shardingSphere, ValidationErrors errors) {
        validateConfigResource(shardingSphere, errors);
    }

    private void validateConfigResource(DynamicJdbcConfig.ShardingSphere shardingSphere, ValidationErrors errors) {
        String configResource = shardingSphere.getConfigResource();
        if (hasText(configResource)) {
            Resource resource = context.getResource(configResource);
            if (!resource.exists()) {
                errors.addError("'{}' modules' 'config-resource'['{}'] can't be found", getModule(), configResource);
            }
        } else {
            errors.addError("'{}' modules' 'config-resource' attribute must not be blank", getModule());
        }
    }
}
