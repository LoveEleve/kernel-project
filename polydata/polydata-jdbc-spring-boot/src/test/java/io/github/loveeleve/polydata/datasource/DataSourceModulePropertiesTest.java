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
package io.github.loveeleve.polydata.datasource;

import io.github.loveeleve.polydata.AbstractTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.MultiValueMap;

import java.util.Set;

import static io.github.loveeleve.polydata.datasource.constants.DataSourceConstants.DATA_SOURCE_MODULE_PROPERTY_NAME_PREFIX;
import static java.util.Collections.emptySet;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DataSourceModuleProperties} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringBootTest(classes = DataSourceModulePropertiesTest.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "classpath:META-INF/dynamic-jdbc/default.properties")
@EnableConfigurationProperties(DataSourceModuleProperties.class)
class DataSourceModulePropertiesTest extends AbstractTest {

    @Autowired
    private DataSourceModuleProperties dataSourceModuleProperties;

    @Autowired
    private ConfigurableEnvironment environment;

    @Test
    void test() {
        Set<String> bannedModules = dataSourceModuleProperties.getBannedModules();
        assertEquals(emptySet(), bannedModules);

        MultiValueMap<String, String> defaultQueryParams = dataSourceModuleProperties.getUrl().getDefaultQueryParams();
        assertEquals(3, defaultQueryParams.size());
        assertEquals("utf-8", defaultQueryParams.getFirst("characterEncoding"));
        assertEquals("false", defaultQueryParams.getFirst("useSSL"));
        assertEquals("true", defaultQueryParams.getFirst("useUnicode"));
    }

    @Test
    void testBinder() {
        ConfigurationPropertySources.attach(environment);
        Binder binder = new Binder(ConfigurationPropertySources.get(environment));
        DataSourceModuleProperties dataSourceModuleProperties =
                binder.bind(DATA_SOURCE_MODULE_PROPERTY_NAME_PREFIX, DataSourceModuleProperties.class).get();

        Set<String> bannedModules = dataSourceModuleProperties.getBannedModules();
        assertEquals(emptySet(), bannedModules);
    }
}
