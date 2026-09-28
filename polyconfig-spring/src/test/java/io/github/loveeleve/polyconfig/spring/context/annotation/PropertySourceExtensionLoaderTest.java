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
package io.github.loveeleve.polyconfig.spring.context.annotation;

import io.github.loveeleve.polyconfig.spring.env.ResourcePropertySourcesRefresher;
import io.github.loveeleve.polyconfig.spring.event.ConfigPropertiesChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AliasFor;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotationMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PropertySourceExtensionLoader}
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class PropertySourceExtensionLoaderTest {

    private GenericApplicationContext context;

    private TestPropertySourceLoader loader;

    @BeforeEach
    void setUp() {
        context = new GenericApplicationContext();
        context.refresh();
        loader = new TestPropertySourceLoader();
        loader.setEnvironment(context.getEnvironment());
        loader.setResourceLoader(context);
        loader.setBeanFactory(context.getBeanFactory());
        TestPropertySourceLoader.lastRefresher = null;
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void testLoadPropertySource() {
        loader.selectImports(AnnotationMetadata.introspect(TestConfig.class));
        assertEquals("polyconfig", context.getEnvironment().getProperty("app.name"));
    }

    @Test
    void testFirstPropertySource() {
        loader.selectImports(AnnotationMetadata.introspect(FirstConfig.class));
        String firstName = context.getEnvironment().getPropertySources().iterator().next().getName();
        assertTrue(firstName.startsWith("TestPropertySource@"), firstName);
    }

    @Test
    void testAutoRefreshUpdatesEnvironmentAndPublishesEvent() {
        List<ConfigPropertiesChangedEvent> events = new ArrayList<>();
        context.addApplicationListener(event -> {
            if (event instanceof ConfigPropertiesChangedEvent) {
                events.add((ConfigPropertiesChangedEvent) event);
            }
        });

        loader.selectImports(AnnotationMetadata.introspect(AutoRefreshConfig.class));
        assertEquals("polyconfig", context.getEnvironment().getProperty("app.name"));

        ResourcePropertySourcesRefresher refresher = TestPropertySourceLoader.lastRefresher;
        assertNotNull(refresher);
        refresher.refresh("app.properties", new ByteArrayResource("app.name=polyconfig-v2".getBytes(UTF_8)));

        assertEquals("polyconfig-v2", context.getEnvironment().getProperty("app.name"));
        assertEquals(1, events.size());
        assertTrue(events.get(0).isChanged("app.name"));
        assertEquals("polyconfig", events.get(0).getOldProperties().get("app.name"));
        assertEquals("polyconfig-v2", events.get(0).getNewProperties().get("app.name"));
    }

    @Test
    void testIgnoreResourceNotFound() {
        loader.selectImports(AnnotationMetadata.introspect(IgnoredMissingConfig.class));
        assertNull(context.getEnvironment().getProperty("app.name"));
    }

    @Test
    void testResourceNotFoundThrows() {
        assertThrows(IllegalStateException.class,
                () -> loader.selectImports(AnnotationMetadata.introspect(MissingConfig.class)));
    }

    @Test
    void testYamlResource() {
        loader.selectImports(AnnotationMetadata.introspect(YamlConfig.class));
        assertEquals("polyconfig-yaml", context.getEnvironment().getProperty("app.name"));
    }

    // ---- End-to-end tests through a real ApplicationContext ----

    @Test
    void testIntegrationWithApplicationContext() {
        try (AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext()) {
            applicationContext.register(IntegrationConfig.class);
            applicationContext.refresh();
            assertEquals("polyconfig", applicationContext.getEnvironment().getProperty("app.name"));
        }
    }

    @Test
    void testIntegrationAutoRefreshPublishesEvent() {
        try (AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext()) {
            List<ConfigPropertiesChangedEvent> events = new ArrayList<>();
            applicationContext.addApplicationListener(event -> {
                if (event instanceof ConfigPropertiesChangedEvent) {
                    events.add((ConfigPropertiesChangedEvent) event);
                }
            });

            applicationContext.register(IntegrationAutoRefreshConfig.class);
            applicationContext.refresh();
            assertEquals("polyconfig", applicationContext.getEnvironment().getProperty("app.name"));

            ResourcePropertySourcesRefresher refresher = TestPropertySourceLoader.lastRefresher;
            assertNotNull(refresher);
            refresher.refresh("app.properties",
                    new ByteArrayResource("app.name=polyconfig-integration-v2".getBytes(UTF_8)));

            assertEquals("polyconfig-integration-v2", applicationContext.getEnvironment().getProperty("app.name"));
            assertEquals(1, events.size());
            assertTrue(events.get(0).isChanged("app.name"));
        }
    }

    @Test
    void testBackendAnnotationWithoutOptionalAttributes() {
        try (AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext()) {
            applicationContext.register(MinimalConfig.class);
            applicationContext.refresh();
            assertEquals("polyconfig", applicationContext.getEnvironment().getProperty("app.name"));
        }
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    @Documented
    @PropertySourceExtension
    @Import(TestPropertySourceLoader.class)
    @interface TestPropertySource {

        @AliasFor(annotation = PropertySourceExtension.class)
        String name() default "";

        @AliasFor(annotation = PropertySourceExtension.class)
        String[] value() default {};

        @AliasFor(annotation = PropertySourceExtension.class)
        boolean autoRefreshed() default false;

        @AliasFor(annotation = PropertySourceExtension.class)
        boolean first() default false;

        @AliasFor(annotation = PropertySourceExtension.class)
        String before() default "";

        @AliasFor(annotation = PropertySourceExtension.class)
        String after() default "";

        @AliasFor(annotation = PropertySourceExtension.class)
        boolean ignoreResourceNotFound() default false;

        @AliasFor(annotation = PropertySourceExtension.class)
        String encoding() default "UTF-8";
    }

    static class TestPropertySourceLoader extends PropertySourceExtensionLoader<TestPropertySource> {

        static volatile ResourcePropertySourcesRefresher lastRefresher;

        @Override
        protected Class<TestPropertySource> getAnnotationType() {
            return TestPropertySource.class;
        }

        @Override
        protected Resource[] resolveResources(AnnotationAttributes attributes, String propertySourceName,
                                              String resourceValue) {
            return new Resource[]{new ClassPathResource(resourceValue)};
        }

        @Override
        protected void configureRefresher(AnnotationAttributes attributes, ResourcePropertySourcesRefresher refresher) {
            lastRefresher = refresher;
        }
    }

    @TestPropertySource("app.properties")
    static class TestConfig {
    }

    @TestPropertySource(value = "app.properties", first = true)
    static class FirstConfig {
    }

    @TestPropertySource(value = "app.properties", autoRefreshed = true)
    static class AutoRefreshConfig {
    }

    @TestPropertySource(value = "missing.properties", ignoreResourceNotFound = true)
    static class IgnoredMissingConfig {
    }

    @TestPropertySource("missing.properties")
    static class MissingConfig {
    }

    @TestPropertySource("app.yml")
    static class YamlConfig {
    }

    @TestPropertySource("app.properties")
    @Configuration
    static class IntegrationConfig {
    }

    @TestPropertySource(value = "app.properties", autoRefreshed = true)
    @Configuration
    static class IntegrationAutoRefreshConfig {
    }

    /**
     * A minimal backend annotation that only declares {@code value}, to verify that the loader
     * applies defaults for the optional attributes.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    @Documented
    @PropertySourceExtension
    @Import(MinimalTestPropertySourceLoader.class)
    @interface MinimalTestPropertySource {

        String[] value() default {};
    }

    static class MinimalTestPropertySourceLoader extends PropertySourceExtensionLoader<MinimalTestPropertySource> {

        @Override
        protected Class<MinimalTestPropertySource> getAnnotationType() {
            return MinimalTestPropertySource.class;
        }

        @Override
        protected Resource[] resolveResources(AnnotationAttributes attributes, String propertySourceName,
                                              String resourceValue) {
            return new Resource[]{new ClassPathResource(resourceValue)};
        }
    }

    @MinimalTestPropertySource("app.properties")
    @Configuration
    static class MinimalConfig {
    }
}
