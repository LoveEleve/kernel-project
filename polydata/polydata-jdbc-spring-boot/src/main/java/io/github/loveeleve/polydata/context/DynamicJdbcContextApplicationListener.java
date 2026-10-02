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
import io.github.loveeleve.polydata.context.error.DynamicJdbcInitializeException;
import io.github.loveeleve.polydata.context.error.InitializeErrors;
import io.github.loveeleve.polydata.support.OnceApplicationPreparedEventListener;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static io.github.loveeleve.polydata.support.ConfigurableAutoConfigurationImportFilter.addExcludedAutoConfigurationClasses;
import static io.github.loveeleve.polydata.util.DynamicJdbcConfigUtils.getDynamicJdbcConfigs;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getAllModulesAutoConfigurationClassNames;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.getMultipleContextExclusionAutoConfigurationClassNames;
import static io.github.loveeleve.polydata.util.DynamicJdbcPropertyUtils.isDynamicJdbcEnabled;
import static java.util.concurrent.Executors.newFixedThreadPool;

/**
 * {@link ApplicationPreparedEvent} {@link ApplicationListener} to prepare for Dynamic JDBC
 * {@link ApplicationContext context} environment
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class DynamicJdbcContextApplicationListener extends OnceApplicationPreparedEventListener {

    /**
     * Default order
     */
    public static final int DEFAULT_ORDER = 200;

    public DynamicJdbcContextApplicationListener() {
        super();
        setOrder(DEFAULT_ORDER);
    }

    @Override
    protected void onMainApplicationPrepared(SpringApplication springApplication, String[] args, ConfigurableApplicationContext context) {

        if (isDisable(context)) {
            logger.debug("Current ApplicationContext[id : {}] disable Dynamic JDBC", context.getId());
            return;
        }

        processDynamicJdbcContext(context);
    }

    private boolean isDisable(ConfigurableApplicationContext context) {
        return !isDynamicJdbcEnabled(context.getEnvironment());
    }

    private void processDynamicJdbcContext(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();

        Map<String, DynamicJdbcConfig> dynamicJdbcConfigs = getDynamicJdbcConfigs(environment);

        int dynamicJdbcConfigSize = dynamicJdbcConfigs.size();

        if (dynamicJdbcConfigSize == 0) {
            logger.info("No DynamicJdbcConfig was configured, The process of Dynamic JDBC Context will be ignored");
            return;
        }

        registerPropagatingDynamicJdbcConfigChangedEventListener(dynamicJdbcConfigs, context);

        boolean multiple = dynamicJdbcConfigSize > 1;

        if (multiple) {
            logger.info("{} DynamicJdbcConfigs were found, Current ApplicationContext[id : {}] will create DynamicJdbcChildContexts individually!",
                    dynamicJdbcConfigSize, context.getId());
            processDynamicJdbcChildContexts(dynamicJdbcConfigs.entrySet(), context);

        } else { // Single
            logger.info("Only one DynamicJdbcConfig was configured, Dynamic JDBC context will use current context[id : {}]!", context.getId());
            Map.Entry<String, DynamicJdbcConfig> dynamicJdbcConfigEntry = dynamicJdbcConfigs.entrySet().iterator().next();
            processDynamicJdbcContext(dynamicJdbcConfigEntry, context);
        }


    }

    private void registerPropagatingDynamicJdbcConfigChangedEventListener(Map<String, DynamicJdbcConfig> dynamicJdbcConfigs,
                                                                          ConfigurableApplicationContext context) {
        context.addApplicationListener(new PropagatingDynamicJdbcConfigChangedEventListener(dynamicJdbcConfigs.keySet(), context));
    }

    private void processDynamicJdbcChildContexts(Set<Map.Entry<String, DynamicJdbcConfig>> dynamicJdbcConfigEntrySet,
                                                 ConfigurableApplicationContext context) {
        int parallelism = dynamicJdbcConfigEntrySet.size();

        ThreadPoolExecutor executorService = (ThreadPoolExecutor) newFixedThreadPool(parallelism);
        InitializeErrors initializeErrors = new InitializeErrors();


        for (Map.Entry<String, DynamicJdbcConfig> dynamicJdbcConfigEntry : dynamicJdbcConfigEntrySet) {
            executorService.execute(() -> {
                try {
                    initializeDynamicJdbcChildContext(dynamicJdbcConfigEntry, context);
                } catch (Throwable t) {
                    initializeErrors.addError(dynamicJdbcConfigEntry.getKey(), t);
                    logger.error("Initialize Dynamic-JDBC failed. DynamicJdbcConfig:[{}]",
                            dynamicJdbcConfigEntry.getKey(), t);
                }
            });
        }

        // All the tasks have been submitted, shut down the executor so that the termination can be
        // awaited properly
        executorService.shutdown();

        try {
            while (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                logger.debug("Waiting for the initialization of {} DynamicJdbcChildContext(s) to be completed ...",
                        parallelism);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("The initialization of the DynamicJdbcChildContexts was interrupted");
        }

        if (initializeErrors.hasError()) {
            throw new DynamicJdbcInitializeException(initializeErrors.toString());
        }

        appendExclusionAutoConfigurationProperty(context);
    }

    private void initializeDynamicJdbcChildContext(Map.Entry<String, DynamicJdbcConfig> dynamicJdbcConfigEntry,
                                                   ConfigurableApplicationContext parentContext) {
        DynamicJdbcConfig dynamicJdbcConfig = dynamicJdbcConfigEntry.getValue();
        String dynamicJdbcConfigPropertyName = dynamicJdbcConfigEntry.getKey();

        DynamicJdbcChildContext dynamicJdbcChildContext =
                new DynamicJdbcChildContext(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, parentContext);

        // Register Parent Beans
        dynamicJdbcChildContext.registerParentBeans();
        // Merge Parent Environment
        dynamicJdbcChildContext.mergeParentEnvironment();
        // Refresh Context
        dynamicJdbcChildContext.refresh();
    }

    private void appendExclusionAutoConfigurationProperty(ConfigurableApplicationContext context) {

        ConfigurableEnvironment environment = context.getEnvironment();

        Set<String> exclusionAutoConfigurationClassNames = getMultipleContextExclusionAutoConfigurationClassNames(environment);

        if (exclusionAutoConfigurationClassNames.isEmpty()) {
            exclusionAutoConfigurationClassNames = getAllModulesAutoConfigurationClassNames(context);
        }

        addExcludedAutoConfigurationClasses(environment, exclusionAutoConfigurationClassNames);
    }

    protected void processDynamicJdbcContext(Map.Entry<String, DynamicJdbcConfig> dynamicJdbcConfigEntry, ConfigurableApplicationContext context) {
        DynamicJdbcContextProcessor processor = new DynamicJdbcContextProcessor();
        DynamicJdbcConfig dynamicJdbcConfig = dynamicJdbcConfigEntry.getValue();
        String dynamicJdbcConfigPropertyName = dynamicJdbcConfigEntry.getKey();
        processor.process(dynamicJdbcConfig, dynamicJdbcConfigPropertyName, context);
    }
}
