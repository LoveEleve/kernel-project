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
import io.github.loveeleve.polydata.config.validation.AbstractConfigValidator;
import io.github.loveeleve.polydata.config.validation.ConfigValidator;
import io.github.loveeleve.polydata.config.validation.ValidationErrors;
import io.github.loveeleve.polydata.datasource.constants.DataSourceConstants;
import io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.DATASOURCE_MODULE;
import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.HIGH_AVAILABILITY_DATASOURCE_MODULE;
import static io.github.loveeleve.polydata.support.ClassLoaderUtils.isPresent;
import static io.github.loveeleve.polydata.support.FormatUtils.format;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE;
import static java.util.Collections.emptyList;

/**
 * JDBC {@link ConfigValidator} for Datasource Properties
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class DataSourcePropertiesModuleValidator extends AbstractConfigValidator {

    @Override
    public void validate(DynamicJdbcConfig dynamicJdbcConfig, String shardingJdbConfigPropertyName, ValidationErrors validationErrors) {
        validateDataSource(dynamicJdbcConfig, validationErrors);
        if (dynamicJdbcConfig.hasDataSource() || dynamicJdbcConfig.hasHighAvailabilityDataSource()) {
            validateDataSourcePropertiesList(dynamicJdbcConfig, validationErrors);
        }
    }

    private void validateDataSource(DynamicJdbcConfig dynamicJdbcConfig, ValidationErrors validationErrors) {
        boolean hasDataSource = dynamicJdbcConfig.hasDataSource();
        boolean hasHighAvailabilityDataSource = dynamicJdbcConfig.hasHighAvailabilityDataSource();

        if (!hasDataSource && !hasHighAvailabilityDataSource) {
            validationErrors.addError("'datasource' or 'ha-datasource' module must be present", dynamicJdbcConfig.getName());
        } else if (hasHighAvailabilityDataSource) {
            if (hasDataSource) {
                validationErrors.addError("'datasource' and 'ha-datasource' module must not be present at the same time",
                        dynamicJdbcConfig.getName());
            }
            validateHighAvailabilityDataSource(dynamicJdbcConfig, validationErrors);
        }
    }

    private void validateHighAvailabilityDataSource(DynamicJdbcConfig dynamicJdbcConfig, ValidationErrors validationErrors) {
        Map<String, List<Map<String, Object>>> highAvailabilityDataSource = dynamicJdbcConfig.getHighAvailabilityDataSource();

        int size = highAvailabilityDataSource.size();

        if (size < 2) {
            validationErrors.addError("'ha-datasource' module must configure 2 or above datasource properties , actual : '{}'", size);
        }

        if (!highAvailabilityDataSource.containsKey(DEFAULT_ZONE)) {
            validationErrors.addError("'ha-datasource' module must configure default zone '{}'", DEFAULT_ZONE);
        }
    }

    private void validateDataSourcePropertiesList(DynamicJdbcConfig dynamicJdbcConfig, ValidationErrors validationErrors) {
        List<Map<String, String>> dataSourcePropertiesList = dynamicJdbcConfig.getDataSourcePropertiesList();
        int size = dataSourcePropertiesList.size();
        for (int index = 0; index < size; index++) {
            Map<String, String> dataSourceProperties = dataSourcePropertiesList.get(index);
            validateDataSourceProperties(dynamicJdbcConfig, dataSourceProperties, index, validationErrors);
            List<Map<String, String>> previousDataSourcePropertiesList = getPreviousDataSourcePropertiesList(dataSourcePropertiesList, index);
            validateDuplicatedProperties(dynamicJdbcConfig, dataSourceProperties, previousDataSourcePropertiesList, index, validationErrors,
                    DataSourceConstants.NAME_PROPERTY_NAME, DataSourceConstants.JDBC_URL_PROPERTY_NAME);
        }
    }

    private List<Map<String, String>> getPreviousDataSourcePropertiesList(List<Map<String, String>> dataSourcePropertiesList, int index) {
        if (index < 1) {
            return emptyList();
        }
        return dataSourcePropertiesList.subList(0, index);
    }

    private void validateDuplicatedProperties(DynamicJdbcConfig dynamicJdbcConfig, Map<String, String> dataSourceProperties,
                                              List<Map<String, String>> previousDataSourcePropertiesList, int index, ValidationErrors errors, String... propertyNames) {
        for (Map<String, String> previousDataSourceProperties : previousDataSourcePropertiesList) {
            for (String propertyName : propertyNames) {
                String previousPropertyValue = previousDataSourceProperties.get(propertyName);
                String propertyValue = dataSourceProperties.get(propertyName);
                if (StringUtils.hasText(previousPropertyValue) && StringUtils.hasText(propertyValue)
                        && Objects.equals(previousPropertyValue, propertyValue)) {
                    String previousDataSourceModuleInfo = getDataSourceModuleInfo(dynamicJdbcConfig, previousDataSourceProperties, index);
                    String dataSourceModuleInfo = getDataSourceModuleInfo(dynamicJdbcConfig, dataSourceProperties, index);
                    errors.addError("The duplicated property [name : '{}' , value : '{}'] appears between {} and {}", propertyName, propertyValue,
                            previousDataSourceModuleInfo, dataSourceModuleInfo);
                }
            }
        }
    }

    private void validateDataSourceProperties(DynamicJdbcConfig dynamicJdbcConfig, Map<String, String> dataSourceProperties, int index,
                                              ValidationErrors errors) {
        validateDataSourceRequiredProperties(dynamicJdbcConfig, dataSourceProperties, index, errors, DataSourceConstants.NAME_PROPERTY_NAME, DataSourceConstants.TYPE_PROPERTY_NAME,
                DataSourceConstants.DRIVER_CLASS_NAME_PROPERTY_NAME, DataSourceConstants.USER_NAME_PROPERTY_NAME, DataSourceConstants.PASSWORD_NAME_PROPERTY_NAME);
        validateDataSourceClassesPresent(dynamicJdbcConfig, dataSourceProperties, index, errors, DataSourceConstants.TYPE_PROPERTY_NAME,
                DataSourceConstants.DRIVER_CLASS_NAME_PROPERTY_NAME);
        validateDataSourceJdbcUrl(dynamicJdbcConfig, dataSourceProperties, index, errors);
    }

    private void validateDataSourceRequiredProperties(DynamicJdbcConfig dynamicJdbcConfig, Map<String, String> dataSourceProperties, int index,
                                                      ValidationErrors errors, String... propertyNames) {
        for (String propertyName : propertyNames) {
            validateDataSourceRequiredProperty(dynamicJdbcConfig, dataSourceProperties, index, errors, propertyName);
        }
    }

    private void validateDataSourceRequiredProperty(DynamicJdbcConfig dynamicJdbcConfig, Map<String, String> dataSourceProperties, int index,
                                                    ValidationErrors errors, String propertyName) {
        String propertyValue = dataSourceProperties.get(propertyName);
        if (!StringUtils.hasText(propertyValue)) {
            String info = getDataSourceModuleInfo(dynamicJdbcConfig, dataSourceProperties, index);
            errors.addError("{} must have '{}' property", info, propertyName);
        }
    }

    private void validateDataSourceClassesPresent(DynamicJdbcConfig dynamicJdbcConfig, Map<String, String> dataSourceProperties, int index,
                                                  ValidationErrors errors, String... propertyNames) {
        for (String propertyName : propertyNames) {
            validateDataSourceClassPresent(dynamicJdbcConfig, dataSourceProperties, index, errors, propertyName);
        }
    }

    private void validateDataSourceClassPresent(DynamicJdbcConfig dynamicJdbcConfig, Map<String, String> dataSourceProperties, int index,
                                                ValidationErrors errors, String propertyName) {
        String className = dataSourceProperties.get(propertyName);
        if (!isPresent(className, classLoader)) {
            String info = getDataSourceModuleInfo(dynamicJdbcConfig, dataSourceProperties, index);
            errors.addError("{} '{}' property -> class '{}' can't be found", info, propertyName, className);
        }
    }

    private void validateDataSourceJdbcUrl(DynamicJdbcConfig dynamicJdbcConfig, Map<String, String> dataSourceProperties, int index,
                                           ValidationErrors errors) {
        Map.Entry<String, String> jdbcURLEntry = DynamicJdbcConfigUtils.getDataSourceUrlEntry(dataSourceProperties);
        String jdbcURL = null;
        if (jdbcURLEntry != null) {
            jdbcURL = jdbcURLEntry.getValue();
        }

        if (!StringUtils.hasText(jdbcURL)) {
            String info = getDataSourceModuleInfo(dynamicJdbcConfig, dataSourceProperties, index);
            errors.addError("{} '{}' property must be present", info, Arrays.asList(DataSourceConstants.JDBC_URL_PROPERTY_NAMES));
        }
    }

    private String getDataSourceModuleInfo(DynamicJdbcConfig dynamicJdbcConfig, Map<String, String> dataSourceProperties, int index) {
        boolean sourceFromHighAvailabilityDataSource = dynamicJdbcConfig.hasHighAvailabilityDataSource();
        String name = DynamicJdbcConfigUtils.getDataSourceName(dataSourceProperties);

        final String info;

        if (sourceFromHighAvailabilityDataSource) {
            String zone = dynamicJdbcConfig.getZoneContext().getZone();
            info = format("{} module [index : {} , name : '{}' , zone : '{}']", HIGH_AVAILABILITY_DATASOURCE_MODULE, index, name, zone);
        } else {
            info = format("{} module [index : {} , name : '{}']", DATASOURCE_MODULE, index, name);;
        }

        return info;
    }
}
