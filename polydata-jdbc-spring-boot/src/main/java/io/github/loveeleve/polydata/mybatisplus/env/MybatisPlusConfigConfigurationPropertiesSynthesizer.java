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
package io.github.loveeleve.polydata.mybatisplus.env;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.env.AbstractConfigurationConfigConfigurationPropertiesSynthesizer;
import io.github.loveeleve.polydata.env.ConfigConfigurationPropertiesSynthesizer;
import io.github.loveeleve.polydata.mybatisplus.constants.MybatisPlusConstants;

import java.util.Map;

import static io.github.loveeleve.polydata.mybatis.env.MybatisConfigConfigurationPropertiesSynthesizer.synthesizeBasePackagesConfigurationProperties;

/**
 * {@link ConfigConfigurationPropertiesSynthesizer} for Mybatis Plus
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 * @see MybatisPlusProperties
 */
public class MybatisPlusConfigConfigurationPropertiesSynthesizer
        extends AbstractConfigurationConfigConfigurationPropertiesSynthesizer<DynamicJdbcConfig.MybatisPlus> {

    @Override
    protected String getConfigurationPropertiesClassName() {
        return "com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties";
    }

    @Override
    protected void synthesize(DynamicJdbcConfig dynamicJdbcConfig, String module, DynamicJdbcConfig.MybatisPlus configuration,
                              Class<?> configurationPropertiesClass, Map<String, Object> properties) {
        synthesizeMybatisPlusBasePackages(configuration, properties);
    }

    private void synthesizeMybatisPlusBasePackages(DynamicJdbcConfig.MybatisPlus mybatisPlus, Map<String, Object> properties) {
        String propertyName = MybatisPlusConstants.DYNAMIC_JDBC_MYBATIS_PLUS_CLASS_PREFIXES_PROPERTY_NAME;
        synthesizeBasePackagesConfigurationProperties(mybatisPlus.getBasePackages(), properties, propertyName, environment);
    }
}
