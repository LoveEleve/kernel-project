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

import org.springframework.core.env.PropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The meta-annotation of a declarative external {@link PropertySource}, the PolyConfig counterpart
 * of Spring's {@code @PropertySource} with configuration center capabilities.
 *
 * <p>
 * A backend annotation (e.g. {@code @NacosPropertySource}) must be annotated with this
 * meta-annotation, declare its own attributes and map them with
 * {@link org.springframework.core.annotation.AliasFor @AliasFor}. The backend annotation also
 * declares {@code @Import(TheBackendLoader.class)}, so that the loader is triggered while the
 * configuration class is being parsed.
 *
 * <p>
 * Example:
 * <pre>
 * &#64;NacosPropertySource(value = "app.properties", group = "DEFAULT_GROUP", autoRefreshed = true)
 * &#64;Configuration
 * public class ApplicationConfig {
 * }
 * </pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface PropertySourceExtension {

    /**
     * The name of the {@link PropertySource}; when empty, a name is generated from the backend
     * annotation and the annotated class.
     */
    String name() default "";

    /**
     * The keys of the property source resources to be loaded, interpreted by the backend (e.g. the
     * Nacos dataId).
     */
    String[] value() default {};

    /**
     * Whether the property source is refreshed automatically when the configuration changes on the
     * server side.
     */
    boolean autoRefreshed() default false;

    /**
     * Whether the property source is added as the first one of the {@code Environment}; when
     * {@code true}, {@link #before()} and {@link #after()} are ignored.
     */
    boolean first() default false;

    /**
     * The name of the property source before which this property source is added.
     */
    String before() default "";

    /**
     * The name of the property source after which this property source is added.
     */
    String after() default "";

    /**
     * Whether a failure to find a resource should be ignored.
     */
    boolean ignoreResourceNotFound() default false;

    /**
     * The character encoding of the resources.
     */
    String encoding() default "UTF-8";
}
