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
package io.github.loveeleve.polydata.datasource.config;

import io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils;
import io.github.loveeleve.polydata.util.URLUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.util.Map;

/**
 * Dynamic JDBC URL Assembler
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class JdbcURLAssembler {

    private static final Logger logger = LoggerFactory.getLogger(JdbcURLAssembler.class);

    private static final String JDBC_URL_PREFIX = "jdbc:";

    private static final int JDBC_URL_PREFIX_LENGTH = JDBC_URL_PREFIX.length();

    private static final String PROTOCOL_SEPARATOR = "//";

    private final ConfigurableEnvironment environment;

    private final String defaultScheme;

    private final Map<String, String> defaultQueryParams;


    public JdbcURLAssembler(ConfigurableEnvironment environment) {
        this.environment = environment;
        this.defaultScheme = DynamicJdbcPropertyUtils.getDataSourceUrlDefaultScheme(environment);
        this.defaultQueryParams = DynamicJdbcPropertyUtils.getDataSourceUrlDefaultQueryParams(environment);
    }

    public String assemble(String rawJdbcURL) {

        logger.debug("The raw JDBC URL : {}", rawJdbcURL);

        if (StringUtils.isBlank(rawJdbcURL)) {
            return rawJdbcURL;
        }

        String url = normalize(rawJdbcURL);

        URI uri = URI.create(url);

        // The opaque JDBC URL(e.g. "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1") has no authority and query
        // string, the default query parameters are not applicable.
        if (uri.isOpaque()) {
            url = JDBC_URL_PREFIX + url;
            logger.debug("The assembled JDBC URL : {}", url);
            return url;
        }

        MultiValueMap<String, String> queryParams = URLUtils.parseQueryParams(uri);

        setDefaultQueryParamsIfAbsent(queryParams);

        url = JDBC_URL_PREFIX + rebuildURL(uri, queryParams);

        logger.debug("The assembled JDBC URL : {}", url);

        return url;
    }

    private String normalize(String rawJdbcURL) {
        String url = StringUtils.trim(rawJdbcURL);
        String protocol = StringUtils.substringBetween(url, JDBC_URL_PREFIX, PROTOCOL_SEPARATOR);
        if (protocol != null) {
            url = StringUtils.substring(rawJdbcURL, JDBC_URL_PREFIX_LENGTH);
        } else if (url.startsWith(JDBC_URL_PREFIX)) {
            // A JDBC URL without the "//" separator(e.g. "jdbc:h2:mem:test") keeps its own scheme
            url = StringUtils.substring(url, JDBC_URL_PREFIX_LENGTH);
        } else {
            url = defaultScheme + url;
        }
        return url;
    }

    private void setDefaultQueryParamsIfAbsent(MultiValueMap<String, String> queryParams) {
        logger.debug("The original query parameters : {}", queryParams);
        defaultQueryParams.forEach((name, value) -> {
            if (!queryParams.containsKey(name)) {
                queryParams.add(name, value);
            }
        });
    }

    private String rebuildURL(URI uri, MultiValueMap<String, String> queryParams) {
        String queryString = uri.getQuery();
        String newQueryString = URLUtils.buildQueryString(queryParams);
        String url = uri.toString();
        if (StringUtils.isNotBlank(queryString)) {
            return StringUtils.replace(url, queryString, newQueryString);
        } else {
            return url + URLUtils.QUERY_STRING_SEPARATOR + newQueryString;
        }
    }

}
