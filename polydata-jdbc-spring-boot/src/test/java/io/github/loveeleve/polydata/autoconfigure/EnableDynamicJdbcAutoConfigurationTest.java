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
package io.github.loveeleve.polydata.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.test.context.SpringBootTest;

import static io.github.loveeleve.polydata.autoconfigure.DynamicJdbcAutoConfigurationRepository.getAutoConfigurationClassNames;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnableDynamicJdbcAutoConfiguration} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringBootTest(classes = EnableDynamicJdbcAutoConfigurationTest.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnableDynamicJdbcAutoConfiguration
class EnableDynamicJdbcAutoConfigurationTest implements BeanClassLoaderAware {

    private ClassLoader classLoader;

    @Test
    void test() {
        String[] autoConfigurationClassNames = getAutoConfigurationClassNames(classLoader);
        assertNotNull(autoConfigurationClassNames);
        assertTrue(autoConfigurationClassNames.length > 0);
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }
}
