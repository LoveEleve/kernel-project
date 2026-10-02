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
import io.github.loveeleve.polydata.constants.DynamicJdbcConstants;
import org.springframework.util.StringUtils;

import java.util.List;

import static java.util.Arrays.asList;

/**
 * {@link ConfigValidator} for {@link DynamicJdbcConfig}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class DynamicJdbcConfigValidator extends AbstractConfigValidator {

    private static final List<String> modules = asList(
            DynamicJdbcConstants.DATASOURCE_MODULE,
            DynamicJdbcConstants.HIGH_AVAILABILITY_DATASOURCE_MODULE,
            DynamicJdbcConstants.TRANSACTION_MODULE,
            DynamicJdbcConstants.SHARDING_SPHERE_MODULE,
            DynamicJdbcConstants.MYBATIS_MODULE,
            DynamicJdbcConstants.MYBATIS_PLUS_MODULE
    );

    @Override
    public void validate(DynamicJdbcConfig config, String shardingJdbConfigPropertyName, ValidationErrors errors) {
        validateName(config, errors);
        validateModules(config, errors);
    }

    private void validateName(DynamicJdbcConfig name, ValidationErrors errors) {
        if (!StringUtils.hasText(name.getName())) {
            errors.addError("DynamicJdbcConfig must contain 'name' attribute");
        }
    }

    private void validateModules(DynamicJdbcConfig config, ValidationErrors errors) {
        if (!config.hasDataSource() && !config.hasHighAvailabilityDataSource() && !config.hasTransaction() && !config.hasShardingDataSource()
                && !config.hasMybatis() && !config.hasMybatisPlus()) {
            errors.addError("DynamicJdbcConfig[name : '{}'] must contain one of modules {}", config.getName(), modules);
        }
    }
}
