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

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.List;

/**
 * The utilities class for {@link SpringFactoriesLoader}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class SpringFactoriesLoaderUtils {

    private SpringFactoriesLoaderUtils() {
    }

    /**
     * Load the factories of the specified type from the default Spring factories resource location
     * ({@code META-INF/spring.factories}) and invoke the lifecycle callbacks with the given
     * {@link ApplicationContext}.
     *
     * @param context     the {@link ApplicationContext}
     * @param factoryType the type of factory
     * @param <T>         the type of factory
     * @return the factories, non-null
     */
    public static <T> List<T> loadFactories(ApplicationContext context, Class<T> factoryType) {
        return loadFactories(context, factoryType, null);
    }

    /**
     * Load the factories of the specified type with the optional constructor arguments and invoke
     * the lifecycle callbacks with the given {@link ApplicationContext}.
     *
     * @param context     the {@link ApplicationContext}
     * @param factoryType the type of factory
     * @param args        the constructor arguments, ignored when {@code null} or empty
     * @param <T>         the type of factory
     * @return the factories, non-null
     */
    public static <T> List<T> loadFactories(ApplicationContext context, Class<T> factoryType, Object... args) {
        List<T> factories;
        SpringFactoriesLoader springFactoriesLoader = SpringFactoriesLoader.forDefaultResourceLocation(context.getClassLoader());
        if (args == null || args.length == 0) {
            factories = springFactoriesLoader.load(factoryType);
        } else {
            factories = springFactoriesLoader.load(factoryType, argumentResolver(args),
                    SpringFactoriesLoader.FailureHandler.throwing());
        }
        for (T factory : factories) {
            BeanUtils.invokeAwareInterfaces(factory, context);
            if (factory instanceof InitializingBean) {
                try {
                    ((InitializingBean) factory).afterPropertiesSet();
                } catch (Exception e) {
                    throw new RuntimeException("The 'afterPropertiesSet' method of the factory[class : "
                            + factory.getClass().getName() + "] can't be invoked", e);
                }
            }
        }
        return factories;
    }

    private static SpringFactoriesLoader.ArgumentResolver argumentResolver(Object... args) {
        return new SpringFactoriesLoader.ArgumentResolver() {
            @Override
            public <T> T resolve(Class<T> argumentType) {
                if (argumentType == null) {
                    return null;
                }
                for (Object arg : args) {
                    if (arg != null && argumentType.isInstance(arg)) {
                        return argumentType.cast(arg);
                    }
                }
                return null;
            }
        };
    }
}
