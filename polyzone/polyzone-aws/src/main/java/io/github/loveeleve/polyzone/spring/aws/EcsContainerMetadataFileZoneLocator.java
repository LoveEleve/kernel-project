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
import io.github.loveeleve.polyzone.spring.AbstractZoneLocator;
import io.github.loveeleve.polyzone.spring.ZoneLocator;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The {@link ZoneLocator} based on <a href=
 * "https://docs.aws.amazon.com/AmazonECS/latest/developerguide/container-metadata.html">Amazon ECS
 * container metadata file</a>.
 *
 * <p>
 * Beginning with version 1.15.0 of the Amazon ECS container agent, various container metadata is
 * available within your containers or the host container instance. The metadata file path is exposed
 * by the environment variable {@value #METADATA_FILE_ENV_NAME}.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @see ZoneLocator
 * @see EcsTaskMetadataEndpointV4ZoneLocator
 * @since 1.0.0
 */
public class EcsContainerMetadataFileZoneLocator extends AbstractZoneLocator {

    public static final String METADATA_FILE_ENV_NAME = "ECS_CONTAINER_METADATA_FILE";

    public static final int DEFAULT_ORDER = 5;

    private static final String ZONE_FIELD_NAME = "AvailabilityZone";

    public EcsContainerMetadataFileZoneLocator() {
        super(DEFAULT_ORDER);
    }

    @Override
    public boolean supports(Environment environment) {
        return environment.containsProperty(METADATA_FILE_ENV_NAME);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String locate(Environment environment) {
        String metadataFilePath = environment.getProperty(METADATA_FILE_ENV_NAME);
        String zone = null;
        if (StringUtils.hasText(metadataFilePath)) {
            File metadataFile = new File(metadataFilePath);
            if (metadataFile.canRead()) {
                try (InputStream inputStream = new FileInputStream(metadataFile)) {
                    String json = new String(inputStream.readAllBytes(), UTF_8);
                    ObjectMapper objectMapper = new ObjectMapper();
                    Map<String, Object> metadata = objectMapper.readValue(json, Map.class);
                    Object zoneValue = metadata.get(ZONE_FIELD_NAME);
                    zone = zoneValue == null ? null : String.valueOf(zoneValue);
                    logger.info("The zone ['{}'] was located from the Amazon ECS Container metadata file "
                                    + "[path: : '{}' , property name: '{}']", zone, metadataFile.getAbsolutePath(),
                            METADATA_FILE_ENV_NAME);
                } catch (Throwable e) {
                    logger.error("Read Amazon ECS container metadata file [path : '{}'] failed",
                            metadataFile.getAbsolutePath(), e);
                }
            } else {
                logger.warn("The Amazon ECS Container metadata file [path : '{}' ] can't be read",
                        metadataFile.getAbsolutePath());
            }
        } else {
            logger.trace("The environment variable [name : '{}' ] of Amazon ECS container metadata file can't be found",
                    METADATA_FILE_ENV_NAME);
        }
        return zone;
    }

}
