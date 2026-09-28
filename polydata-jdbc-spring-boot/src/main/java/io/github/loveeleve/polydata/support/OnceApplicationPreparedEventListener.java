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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link ApplicationListener} on the {@link ApplicationPreparedEvent} that only processes the
 * main (non Spring Cloud bootstrap) {@link ConfigurableApplicationContext} once.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class OnceApplicationPreparedEventListener implements ApplicationListener<ApplicationPreparedEvent>, Ordered {

    /**
     * The id of Spring Cloud bootstrap context
     */
    public static final String BOOTSTRAP_CONTEXT_ID = "bootstrap";

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private final Set<String> processedContextIds = ConcurrentHashMap.newKeySet();

    private int order = Ordered.LOWEST_PRECEDENCE;

    @Override
    public final void onApplicationEvent(ApplicationPreparedEvent event) {
        ConfigurableApplicationContext context = event.getApplicationContext();
        String contextId = context.getId();
        if (isBootstrapContext(context)) {
            logger.debug("Current ApplicationContext[id : {}] is a Spring Cloud bootstrap context, the event will be ignored", contextId);
            return;
        }
        if (!processedContextIds.add(contextId)) {
            logger.debug("Current ApplicationContext[id : {}] has been processed, the event will be ignored", contextId);
            return;
        }
        onMainApplicationPrepared(event.getSpringApplication(), event.getArgs(), context);
    }

    /**
     * Whether the given {@link ConfigurableApplicationContext} is a Spring Cloud bootstrap context.
     *
     * @param context the {@link ConfigurableApplicationContext}
     * @return {@code true} if it is a bootstrap context
     */
    protected boolean isBootstrapContext(ConfigurableApplicationContext context) {
        return StringUtils.hasText(context.getId()) && BOOTSTRAP_CONTEXT_ID.equals(context.getId());
    }

    /**
     * Callback on the main {@link ConfigurableApplicationContext} has been prepared.
     *
     * @param springApplication the {@link SpringApplication}
     * @param args              the command line arguments
     * @param context           the main {@link ConfigurableApplicationContext}
     */
    protected abstract void onMainApplicationPrepared(SpringApplication springApplication, String[] args,
                                                      ConfigurableApplicationContext context);

    @Override
    public final int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }
}
