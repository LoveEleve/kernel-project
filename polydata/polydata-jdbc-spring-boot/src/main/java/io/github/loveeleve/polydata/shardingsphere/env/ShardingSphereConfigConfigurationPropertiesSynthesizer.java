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
package io.github.loveeleve.polydata.shardingsphere.env;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.constants.DynamicJdbcConstants;
import io.github.loveeleve.polydata.env.AbstractConfigConfigurationPropertiesSynthesizer;
import io.github.loveeleve.polydata.env.ConfigConfigurationPropertiesSynthesizer;

import java.util.Map;
import java.util.Set;

import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getModuleExclusionAutoConfigurationClassNames;

/**
 * {@link ConfigConfigurationPropertiesSynthesizer} for ShardingSphere.
 *
 * <p>
 * Since ShardingSphere JDBC 5.5.1 is integrated through
 * {@link org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory} instead of
 * the ShardingSphere Spring Boot starter, no {@code spring.shardingsphere.*} property is synthesized
 * any more. This synthesizer only excludes the auto-configuration classes banned by the
 * "sharding-sphere" module (e.g. Spring Boot {@code DataSourceAutoConfiguration}), so that the
 * ShardingSphere DataSource is the only {@link javax.sql.DataSource} in the child context.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class ShardingSphereConfigConfigurationPropertiesSynthesizer extends AbstractConfigConfigurationPropertiesSynthesizer {

    @Override
    public void synthesize(DynamicJdbcConfig dynamicJdbcConfig, Map<String, Object> properties) {
        if (dynamicJdbcConfig.getShardingSphere() == null) {
            return;
        }
        Set<String> exclusionAutoConfigurationClassNames =
                getModuleExclusionAutoConfigurationClassNames(context, DynamicJdbcConstants.SHARDING_SPHERE_MODULE);
        excludeAutoConfigurationProperty(properties, exclusionAutoConfigurationClassNames);
    }
}
