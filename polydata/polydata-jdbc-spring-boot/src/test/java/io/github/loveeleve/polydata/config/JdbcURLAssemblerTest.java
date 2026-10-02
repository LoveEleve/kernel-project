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

import io.github.loveeleve.polydata.datasource.config.JdbcURLAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JdbcURLAssembler} Test
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@SpringJUnitConfig(classes = {JdbcURLAssembler.class, JdbcURLAssemblerTest.class})
@TestPropertySource(locations = {"classpath:/META-INF/dynamic-jdbc/default.properties"})
class JdbcURLAssemblerTest {

    @Autowired
    private JdbcURLAssembler jdbcURLAssembler;

    @Test
    void testAssemble() {
        String rawJdbcURL = "jdbc:mysql://127.0.0.1:3306/demo_ds";
        String jdbcURL = jdbcURLAssembler.assemble(rawJdbcURL);
        assertEquals("jdbc:mysql://127.0.0.1:3306/demo_ds?characterEncoding=utf-8&useSSL=false&useUnicode=true", jdbcURL);
    }

    @Test
    void testAssembleWithoutScheme() {
        String rawJdbcURL = "127.0.0.1:3306/demo_ds?characterEncoding=UTF-8&ttl=false&useUnicode=false";
        String jdbcURL = jdbcURLAssembler.assemble(rawJdbcURL);
        assertEquals("jdbc:mysql://127.0.0.1:3306/demo_ds?characterEncoding=UTF-8&ttl=false&useUnicode=false&useSSL=false",
                jdbcURL);
    }

    @Test
    void testAssembleOpaqueJdbcURL() {
        String rawJdbcURL = "jdbc:h2:mem:polydata;DB_CLOSE_DELAY=-1";
        String jdbcURL = jdbcURLAssembler.assemble(rawJdbcURL);
        assertEquals("jdbc:h2:mem:polydata;DB_CLOSE_DELAY=-1", jdbcURL);
    }
}
