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
package io.github.loveeleve.polydata.shardingsphere.constants;

import static io.github.loveeleve.polydata.constants.DynamicJdbcConstants.PROPERTY_NAME_SEPARATOR;

/**
 * The Constants of Dynamic Sphere
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public interface ShardingSphereConstants {

    /**
     * The module name of ShardingSphere
     */
    String SHARDING_SPHERE_MODULE = "sharding-sphere";

    /**
     * The property name suffix of the ShardingSphere module datasource default properties :
     * "datasource"
     */
    String SHARDING_SPHERE_MODULE_DATASOURCE_DEFAULT_PROPERTIES_SUFFIX = "datasource";

    String SHARDING_SPHERE_PROPERTY_NAME_PREFIX = "spring.shardingsphere";

    String SHARDING_SPHERE_PROPERTIES_PROPERTY_NAME_PREFIX = SHARDING_SPHERE_PROPERTY_NAME_PREFIX + PROPERTY_NAME_SEPARATOR + "props";

    String SHARDING_SPHERE_DATA_SOURCE_PROPERTY_NAME_PREFIX = SHARDING_SPHERE_PROPERTY_NAME_PREFIX + PROPERTY_NAME_SEPARATOR + "datasource";

    String SHARDING_SPHERE_DATA_SOURCE_NAMES_PROPERTY_NAME = SHARDING_SPHERE_DATA_SOURCE_PROPERTY_NAME_PREFIX + PROPERTY_NAME_SEPARATOR + "names";

    String SHARDING_SPHERE_DATASOURCE_CLASS_NAME = "org.apache.shardingsphere.driver.jdbc.core.datasource.ShardingSphereDataSource";
}
