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

import io.github.loveeleve.polydata.AbstractTest;
import io.github.loveeleve.polydata.config.ConfigPostProcessor;
import io.github.loveeleve.polydata.context.ConfigBeanDefinitionRegistrar;
import io.github.loveeleve.polydata.env.ConfigConfigurationPropertiesSynthesizer;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import static io.github.loveeleve.polydata.support.SpringFactoriesLoaderUtils.loadFactories;
import static io.github.loveeleve.polydata.util.DynamicJdbcUtils.resolveDBClusterId;
import static io.github.loveeleve.polydata.util.DynamicJdbcUtils.resolveDatabaseHost;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DynamicJdbcUtils} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class DynamicJdbcUtilsTest extends AbstractTest {

    @Test
    void testLoadSpringFactories() {
        GenericApplicationContext context = new GenericApplicationContext();

        assertEquals(6, loadFactories(context, ConfigPostProcessor.class).size());

        assertEquals(5, loadFactories(context, ConfigConfigurationPropertiesSynthesizer.class).size());

        assertEquals(5, loadFactories(context, ConfigBeanDefinitionRegistrar.class).size());
    }

    @Test
    void testLoadSpringFactoriesWithArgs() {
        GenericApplicationContext context = new GenericApplicationContext();

        assertEquals(6, loadFactories(context, ConfigPostProcessor.class, (Object[]) null).size());

        assertEquals(5, loadFactories(context, ConfigConfigurationPropertiesSynthesizer.class, new Object[0]).size());
    }

    @Test
    void testResolveDatabaseHost() {
        assertEquals("bnb-asset1.cluster-ct1wbptr1zqn.ap-northeast-1.rds.amazonaws.com", resolveDatabaseHost(
                "jdbc:mysql://bnb-asset1.cluster-ct1wbptr1zqn.ap-northeast-1.rds.amazonaws.com/asset?useUnicode=true&characterEncoding=utf-8&useSSL=false"));
        assertEquals("127.0.0.1", resolveDatabaseHost("jdbc:mysql://127.0.0.1:3306/demo_ds?socketTimeout=3000&useUnicode=true"));
        assertEquals("127.0.0.1", resolveDatabaseHost("jdbc:mariadb:aurora//127.0.0.1:3306/demo_ds"));
        assertNull(resolveDatabaseHost("127.0.0.1:3306/demo_ds"));
        assertNull(resolveDatabaseHost(" "));
        assertNull(resolveDatabaseHost(""));
        assertNull(resolveDatabaseHost(null));
    }

    @Test
    void testResolveDatasourceClusterId() {
        assertEquals("bnb-asset1", resolveDBClusterId("bnb-asset1.cluster-ct1wbptr1zqn.ap-northeast-1.rds.amazonaws.com"));
        assertNull(resolveDBClusterId("localhost"));
        assertNull(resolveDBClusterId(" "));
        assertNull(resolveDBClusterId(""));
        assertNull(resolveDBClusterId(null));
    }
}
