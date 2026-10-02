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
package io.github.loveeleve.polyzone.spring.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loveeleve.polyzone.HttpUtils;
import io.github.loveeleve.polyzone.spring.AbstractZoneLocator;
import io.github.loveeleve.polyzone.spring.ZoneLocator;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Map;

import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_TIMEOUT;
import static io.github.loveeleve.polyzone.ZoneConstants.LOCATOR_TIMEOUT_PROPERTY_NAME;

/**
 * The {@link ZoneLocator} based on <a href=
 * "https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-metadata-endpoint-v4.html">Task
 * metadata endpoint version 4</a>.
 *
 * <p>
 * The environment variable {@value #METADATA_URI_V4_ENV_NAME} is injected by default into the
 * containers of Amazon ECS tasks launched on Amazon EC2 Linux instances that are running at least
 * version 1.39.0 of the Amazon ECS container agent.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ZoneLocator
 * @since 1.0.0
 */
public class EcsTaskMetadataEndpointV4ZoneLocator extends AbstractZoneLocator implements EnvironmentAware {

    public static final String METADATA_URI_V4_ENV_NAME = "ECS_CONTAINER_METADATA_URI_V4";

    public static final int DEFAULT_ORDER = 10;

    private static final String ZONE_FIELD_NAME = "AvailabilityZone";

    private int timeout = DEFAULT_TIMEOUT;

    public EcsTaskMetadataEndpointV4ZoneLocator() {
        super(DEFAULT_ORDER);
    }

    @Override
    public boolean supports(Environment environment) {
        return environment.containsProperty(METADATA_URI_V4_ENV_NAME);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String locate(Environment environment) {
        String uri = environment.getProperty(METADATA_URI_V4_ENV_NAME);
        String zone = null;
        if (StringUtils.hasText(uri)) {
            String taskURL = uri + "/task";
            try {
                String json = HttpUtils.doGet(taskURL, timeout);
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> metadata = objectMapper.readValue(json, Map.class);
                Object zoneValue = metadata.get(ZONE_FIELD_NAME);
                zone = zoneValue == null ? null : String.valueOf(zoneValue);
                logger.info("The zone ['{}'] was located from the Amazon ECS Task metadata endpoint version 4 "
                        + "[URL : '{}' , property name: '{}']", zone, taskURL, METADATA_URI_V4_ENV_NAME);
            } catch (Throwable e) {
                logger.error("Request Amazon ECS Task metadata endpoint version 4 [URL : '{}'] failed", taskURL, e);
            }
        } else {
            logger.trace("The environment variable [name : '{}' ] of Amazon ECS Task metadata endpoint version 4 "
                    + "can't be found", METADATA_URI_V4_ENV_NAME);
        }
        return zone;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.timeout = environment.getProperty(LOCATOR_TIMEOUT_PROPERTY_NAME, int.class, DEFAULT_TIMEOUT);
    }
}
