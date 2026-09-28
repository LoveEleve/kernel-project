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
package io.github.loveeleve.polydata.util;

import io.github.loveeleve.polydata.env.ConfigurationPropertiesFlatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.loveeleve.polydata.support.FormatUtils.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static org.apache.commons.lang3.StringUtils.substringBetween;
import static org.springframework.util.StringUtils.delimitedListToStringArray;
import static org.springframework.util.StringUtils.hasText;

/**
 * The utilities class for Dynamic Jdbc
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class DynamicJdbcUtils {

    private static final Logger logger = LoggerFactory.getLogger(DynamicJdbcUtils.class);

    private DynamicJdbcUtils() {
    }

    /**
     * Load the ShardingSphere YAML configuration resource as a byte array, which is the raw
     * argument of
     * {@link org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory}.
     *
     * @param resourceLoader the {@link ResourceLoader}
     * @param configResource the location of ShardingSphere YAML configuration resource
     * @return the YAML content
     */
    public static byte[] loadShardingSphereYamlResource(ResourceLoader resourceLoader, String configResource) {
        Resource resource = resourceLoader.getResource(configResource);
        try (InputStream inputStream = resource.getInputStream()) {
            return StreamUtils.copyToByteArray(inputStream);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    format("ShardingSphere's YAML Config Resource[location : {}] can't be read", configResource), e);
        }
    }

    public static Map<String, String> flatProperties(Map<String, Object> nestedProperties) {
        ConfigurationPropertiesFlatter configurationPropertiesFlatter = ConfigurationPropertiesFlatter.getInstance();
        return configurationPropertiesFlatter.flat(nestedProperties);
    }

    public static List<Map<String, String>> flatPropertiesList(List<Map<String, Object>> nestedPropertiesList) {
        int size = nestedPropertiesList == null ? 0 : nestedPropertiesList.size();

        if (size < 1) {
            return emptyList();
        }

        List<Map<String, String>> flattenPropertiesList = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            flattenPropertiesList.add(flatProperties(nestedPropertiesList.get(i)));
        }

        return unmodifiableList(flattenPropertiesList);
    }

    public static Map<String, List<Map<String, String>>> flatPropertiesMap(Map<String, List<Map<String, Object>>> nestedPropertiesMap) {
        int size = nestedPropertiesMap == null ? 0 : nestedPropertiesMap.size();

        if (size < 1) {
            return emptyMap();
        }

        Map<String, List<Map<String, String>>> flattenPropertiesMap = new LinkedHashMap<>(size);

        nestedPropertiesMap.forEach((name, nestedPropertiesList) -> {
            flattenPropertiesMap.put(name, flatPropertiesList(nestedPropertiesList));
        });

        return unmodifiableMap(flattenPropertiesMap);
    }

    /**
     * Resolve the host of database from the specified JDBC URL
     *
     * @param jdbcURL JDBC URL
     * @return the host of database if resolved, or <code>null</code>
     */
    public static String resolveDatabaseHost(String jdbcURL) {
        if (!hasText(jdbcURL)) {
            return null;
        }
        String address = substringBetween(jdbcURL, "//", "/");
        if (address == null) {
            return null;
        }
        String[] hostAndPort = delimitedListToStringArray(address, ":");
        if (hostAndPort.length < 1) {
            return null;
        }
        String host = hostAndPort[0];
        return host;
    }

    /**
     * Resolve DataBase Cluster ID from JDBC URL when
     * jdbc:mysql://bnb-asset1.cluster-ct1wbptr1zqn.ap-northeast-1.rds.amazonaws.com/asset?useUnicode=true&amp;characterEncoding=utf-8&amp;useSSL=false
     * <p>
     * The default property names of username and password:
     * <p>
     * {service-name}.{db-cluster-id}.username
     * <p>
     * {service-name}.{db-cluster-id}.password
     *
     * @param databaseHost the host of database
     * @return Database Cluster ID if resolved, or <code>null</code>
     */
    public static String resolveDBClusterId(String databaseHost) {
        if (!hasText(databaseHost)) {
            return null;
        }
        String[] parts = delimitedListToStringArray(databaseHost, ".");
        if (parts.length > 1) {
            return parts[0];
        }
        return null;
    }

}
