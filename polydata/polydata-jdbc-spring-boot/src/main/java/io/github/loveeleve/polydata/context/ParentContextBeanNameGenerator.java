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
package io.github.loveeleve.polydata.context;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;

/**
 * Dynamic JDBC Parent Context(main) Bean Name Generator
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 * @param <CB> the type of child bean
 */
public interface ParentContextBeanNameGenerator<CB> {

    @Nullable
    String generate(String childBeanName, CB childBean, DynamicJdbcConfig dynamicJdbcConfig, ConfigurableApplicationContext childContext);

    /**
     * @return Get the type of child bean
     */
    default Class<CB> getChildBeanType() {
        return (Class<CB>) ResolvableType.forClass(getClass()).getInterfaces()[0].getGeneric(0).resolve();
    }

}
