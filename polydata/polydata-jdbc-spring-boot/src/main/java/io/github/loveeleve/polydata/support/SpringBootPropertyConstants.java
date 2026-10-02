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
package io.github.loveeleve.polydata.support;

/**
 * The constants of Spring Boot properties.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public interface SpringBootPropertyConstants {

    /**
     * The property name of excluding auto-configuration classes : "spring.autoconfigure.exclude"
     */
    String SPRING_AUTO_CONFIGURE_EXCLUDE_PROPERTY_NAME = "spring.autoconfigure.exclude";

    /**
     * The property source name that Spring Boot attaches the configuration properties to :
     * "configurationProperties"
     */
    String ATTACHED_PROPERTY_SOURCE_NAME = "configurationProperties";
}
