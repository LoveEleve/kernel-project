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

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import io.github.loveeleve.polyconfig.spring.context.annotation.PropertySourceExtensionLoader;
import io.github.loveeleve.polyconfig.spring.env.ResourcePropertySourcesRefresher;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The {@link NacosPropertySource} loader: it fetches the Nacos configurations into the Spring
 * {@code Environment} and registers the Nacos change listener to refresh them (and publish the
 * unified PolyConfig event) when the configuration changes.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see NacosPropertySource
 * @since 1.0.0
 */
public class NacosPropertySourceLoader extends PropertySourceExtensionLoader<NacosPropertySource> {

    /**
     * The default Nacos group
     */
    public static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    private static final Map<String, ConfigService> configServiceCache = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            configServiceCache.values().forEach(configService -> {
                try {
                    configService.shutDown();
                } catch (Throwable e) {
                    // ignore the failure while shutting down
                }
            });
            configServiceCache.clear();
        }, "polyconfig-nacos-shutdown"));
    }

    @Override
    protected Class<NacosPropertySource> getAnnotationType() {
        return NacosPropertySource.class;
    }

    @Override
    protected Resource[] resolveResources(AnnotationAttributes attributes, String propertySourceName,
                                          String resourceValue) throws Throwable {
        ConfigService configService = getConfigService(attributes);
        String content = configService.getConfig(resourceValue, resolveGroup(attributes), resolveTimeout(attributes));
        if (content == null) {
            return new Resource[0];
        }
        return new Resource[]{new ByteArrayResource(content.getBytes(UTF_8), resourceValue)};
    }

    @Override
    protected void configureRefresher(AnnotationAttributes attributes, ResourcePropertySourcesRefresher refresher)
            throws Throwable {
        ConfigService configService = getConfigService(attributes);
        String group = resolveGroup(attributes);
        for (String dataId : refresher.getResourceValues()) {
            configService.addListener(dataId, group, new Listener() {

                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    if (configInfo != null) {
                        refresher.refresh(dataId, new ByteArrayResource(configInfo.getBytes(UTF_8), dataId));
                    }
                }
            });
            logger.info("The Nacos configuration listener has been registered [dataId : '{}' , group : '{}']", dataId,
                    group);
        }
    }

    /**
     * Get the cached {@link ConfigService} of the given annotation attributes.
     *
     * @param attributes the {@link NacosPropertySource} attributes
     * @return the {@link ConfigService}
     * @throws NacosException if the client can't be created
     */
    protected ConfigService getConfigService(AnnotationAttributes attributes) throws NacosException {
        String serverAddress = attributes.getString("serverAddress");
        String namespace = attributes.getString("namespace");
        String username = attributes.getString("username");
        String cacheKey = serverAddress + "|" + namespace + "|" + username;
        ConfigService configService = configServiceCache.get(cacheKey);
        if (configService == null) {
            configService = createConfigService(attributes);
            configServiceCache.put(cacheKey, configService);
        }
        return configService;
    }

    /**
     * Create a new {@link ConfigService} from the annotation attributes.
     *
     * @param attributes the {@link NacosPropertySource} attributes
     * @return the {@link ConfigService}
     * @throws NacosException if the client can't be created
     */
    protected ConfigService createConfigService(AnnotationAttributes attributes) throws NacosException {
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, attributes.getString("serverAddress"));

        String namespace = attributes.getString("namespace");
        if (StringUtils.hasText(namespace)) {
            properties.put(PropertyKeyConst.NAMESPACE, namespace);
        }
        String username = attributes.getString("username");
        if (StringUtils.hasText(username)) {
            properties.put(PropertyKeyConst.USERNAME, username);
        }
        String password = attributes.getString("password");
        if (StringUtils.hasText(password)) {
            properties.put(PropertyKeyConst.PASSWORD, password);
        }
        return NacosFactory.createConfigService(properties);
    }

    protected String resolveGroup(AnnotationAttributes attributes) {
        String group = attributes.getString("group");
        return StringUtils.hasText(group) ? group : DEFAULT_GROUP;
    }

    protected long resolveTimeout(AnnotationAttributes attributes) {
        Long timeout = attributes.getNumber("timeout");
        return timeout == null ? 3000L : timeout;
    }
}
