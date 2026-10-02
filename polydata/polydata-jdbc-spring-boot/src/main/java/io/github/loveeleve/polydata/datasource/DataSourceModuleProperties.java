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

import io.github.loveeleve.polydata.config.annotation.Module;
import io.github.loveeleve.polydata.context.ModuleProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.DATASOURCE_MODULE;
import static io.github.loveeleve.polydata.datasource.constants.DataSourceConstants.DATA_SOURCE_MODULE_PROPERTY_NAME_PREFIX;

/**
 * {@link ModuleProperties} for {@link DataSource "data-source"}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@Module(DATASOURCE_MODULE)
@ConfigurationProperties(prefix = DATA_SOURCE_MODULE_PROPERTY_NAME_PREFIX)
public class DataSourceModuleProperties extends ModuleProperties {

    private Map<String, List<String>> propertyNameAliases = new LinkedHashMap<>();

    private URL url = new URL();

    public Map<String, List<String>> getPropertyNameAliases() {
        return propertyNameAliases;
    }

    public void setPropertyNameAliases(Map<String, List<String>> propertyNameAliases) {
        this.propertyNameAliases = propertyNameAliases;
    }

    public URL getUrl() {
        return url;
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    public static class URL {

        private MultiValueMap<String, String> defaultQueryParams = new LinkedMultiValueMap<>();

        public MultiValueMap<String, String> getDefaultQueryParams() {
            return defaultQueryParams;
        }

        public void setDefaultQueryParams(MultiValueMap<String, String> defaultQueryParams) {
            this.defaultQueryParams = defaultQueryParams;
        }
    }
}
