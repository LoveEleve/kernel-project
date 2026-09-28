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
package io.github.loveeleve.polyconfig.nacos.annotation;

import io.github.loveeleve.polyconfig.spring.context.annotation.PropertySourceExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.core.env.PropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The declarative Nacos {@link PropertySource}.
 *
 * <p>
 * It loads one or more Nacos configurations (the {@link #value() dataIds}) into the Spring
 * {@code Environment} while the configuration class is being parsed, and keeps them refreshed when
 * the configuration changes on the Nacos server side ({@link #autoRefreshed()}).
 *
 * <p>
 * Example:
 * <pre>
 * &#64;NacosPropertySource(value = "application.properties", group = "DEFAULT_GROUP",
 *                       serverAddress = "127.0.0.1:8848")
 * &#64;Configuration
 * public class NacosConfiguration {
 * }
 * </pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see NacosPropertySourceLoader
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@PropertySourceExtension
@Import(NacosPropertySourceLoader.class)
public @interface NacosPropertySource {

    /**
     * The name of the target {@link PropertySource}.
     */
    @AliasFor(annotation = PropertySourceExtension.class)
    String name() default "";

    /**
     * The Nacos dataIds to be loaded, e.g. {@code "application.properties"} or
     * {@code "application.yaml"}.
     */
    @AliasFor(annotation = PropertySourceExtension.class)
    String[] value() default {};

    /**
     * Whether the property source is auto-refreshed when the configuration changes on the Nacos
     * server side.
     *
     * @return default value is {@code true}
     */
    @AliasFor(annotation = PropertySourceExtension.class)
    boolean autoRefreshed() default true;

    /**
     * Whether the property source is added as the first one of the {@code Environment}.
     */
    @AliasFor(annotation = PropertySourceExtension.class)
    boolean first() default false;

    /**
     * The name of the property source before which this property source is added.
     */
    @AliasFor(annotation = PropertySourceExtension.class)
    String before() default "";

    /**
     * The name of the property source after which this property source is added.
     */
    @AliasFor(annotation = PropertySourceExtension.class)
    String after() default "";

    /**
     * Whether a missing Nacos configuration should be ignored.
     */
    @AliasFor(annotation = PropertySourceExtension.class)
    boolean ignoreResourceNotFound() default false;

    /**
     * The character encoding of the Nacos configuration content.
     */
    @AliasFor(annotation = PropertySourceExtension.class)
    String encoding() default "UTF-8";

    /**
     * The Nacos server address, e.g. {@code "127.0.0.1:8848"} or
     * {@code "nacos.example.com:8848,nacos.example.com:8849"}.
     */
    String serverAddress() default "127.0.0.1:8848";

    /**
     * The Nacos namespace (tenant), empty for the public namespace.
     */
    String namespace() default "";

    /**
     * The Nacos group.
     */
    String group() default NacosPropertySourceLoader.DEFAULT_GROUP;

    /**
     * The Nacos username.
     */
    String username() default "";

    /**
     * The Nacos password.
     */
    String password() default "";

    /**
     * The timeout in milliseconds of fetching the configuration.
     */
    long timeout() default 3000L;
}
