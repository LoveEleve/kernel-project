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
package io.github.loveeleve.polydata.mybatis.env;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.env.AbstractConfigurationConfigConfigurationPropertiesSynthesizer;
import io.github.loveeleve.polydata.env.ConfigConfigurationPropertiesSynthesizer;
import io.github.loveeleve.polydata.mybatis.constants.MybatisConstants;
import org.mybatis.spring.boot.autoconfigure.MybatisProperties;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

import static io.github.loveeleve.polydata.support.EnvironmentUtils.resolveCommaDelimitedValueToList;

/**
 * {@link ConfigConfigurationPropertiesSynthesizer} for Mybatis
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 * @see MybatisProperties
 */
public class MybatisConfigConfigurationPropertiesSynthesizer
        extends AbstractConfigurationConfigConfigurationPropertiesSynthesizer<DynamicJdbcConfig.Mybatis> {

    public static void synthesizeBasePackagesConfigurationProperties(String basePackagesValue, Map<String, Object> properties, String propertyName,
            Environment environment) {
        List<String> basePackages = resolveCommaDelimitedValueToList(environment, basePackagesValue);
        String propertyValue = StringUtils.collectionToCommaDelimitedString(basePackages);
        properties.put(propertyName, propertyValue);
    }

    @Override
    protected String getConfigurationPropertiesClassName() {
        return "org.mybatis.spring.boot.autoconfigure.MybatisProperties";
    }

    @Override
    protected void synthesize(DynamicJdbcConfig dynamicJdbcConfig, String module, DynamicJdbcConfig.Mybatis configuration,
                              Class<?> configurationPropertiesClass, Map<String, Object> properties) {
        synthesizeMybatisBasePackagesConfigurationProperties(configuration, properties);
    }

    private void synthesizeMybatisBasePackagesConfigurationProperties(DynamicJdbcConfig.Mybatis mybatisPlus, Map<String, Object> properties) {
        String propertyName = MybatisConstants.DYNAMIC_JDBC_MYBATIS_CLASS_PREFIXES_PROPERTY_NAME;
        synthesizeBasePackagesConfigurationProperties(mybatisPlus.getBasePackages(), properties, propertyName, environment);
    }


}
