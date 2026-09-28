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
package io.github.loveeleve.polydata.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link AbstractConfigPostProcessor} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringJUnitConfig(classes = AbstractConfigPostProcessorTest.TestConfigPostProcessor.class)
class AbstractConfigPostProcessorTest {

    @Autowired
    private AbstractConfigPostProcessor configPostProcessor;

    @Autowired
    private Environment environment;

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void test() {
        assertEquals(TestConfigPostProcessor.class, configPostProcessor.getClass());
        assertEquals(0, configPostProcessor.getOrder());
        assertSame(environment, configPostProcessor.environment);
        assertSame(context.getClassLoader(), configPostProcessor.classLoader);
        assertSame(context, configPostProcessor.context);
    }

    static class TestConfigPostProcessor extends AbstractConfigPostProcessor {

        @Override
        public void postProcess(DynamicJdbcConfig dynamicJdbcConfig, String dynamicJdbcConfigPropertyName) {

        }
    }
}
