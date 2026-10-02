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
package io.github.loveeleve.polydata.mybatisplus.constants;

import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.BASE_PACKAGES_PROPERTY_NAME;
import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.DYNAMIC_JDBC_MODULES_PROPERTY_NAME_PREFIX;
import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.MYBATIS_PLUS_MODULE;
import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.PROPERTY_NAME_SEPARATOR;

/**
 * The Constants of Mybatis-plus
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public interface MybatisPlusConstants {

    String MYBATIS_PLUS__MODULE_PROPERTY_NAME_PREFIX = DYNAMIC_JDBC_MODULES_PROPERTY_NAME_PREFIX + PROPERTY_NAME_SEPARATOR + MYBATIS_PLUS_MODULE;

    String DYNAMIC_JDBC_MYBATIS_PLUS_CLASS_PREFIXES_PROPERTY_NAME =
            MYBATIS_PLUS__MODULE_PROPERTY_NAME_PREFIX + PROPERTY_NAME_SEPARATOR + BASE_PACKAGES_PROPERTY_NAME;

    String DYNAMIC_JDBC_MYBATIS_PLUS_BASE_PACKAGES_PLACEHOLDER = "${" + DYNAMIC_JDBC_MYBATIS_PLUS_CLASS_PREFIXES_PROPERTY_NAME + "}";

}
