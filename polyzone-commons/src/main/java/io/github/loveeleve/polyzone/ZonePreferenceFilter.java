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
package io.github.loveeleve.polyzone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import static io.github.loveeleve.polyzone.Strings.isBlank;
import static io.github.loveeleve.polyzone.Strings.split;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE;
import static io.github.loveeleve.polyzone.ZoneConstants.PREFERENCE_ENABLED_PROPERTY_NAME;
import static io.github.loveeleve.polyzone.ZoneConstants.ZONE_ENABLED_PROPERTY_NAME;

/**
 * The zone preference filter.
 *
 * <p>
 * The filter keeps the same-zone entities when (and only when) all built-in safety checks pass:
 * <ol>
 *     <li>The zone feature and the zone preference are enabled</li>
 *     <li>The current zone is not blank and not {@value ZoneConstants#DEFAULT_ZONE}</li>
 *     <li>Entities of disabled zones are removed first</li>
 *     <li>The ratio of entities carrying a zone is not under the
 *         {@code zone-ready-percentage} threshold</li>
 *     <li>The number of same-zone entities is not under the
 *         {@code same-zone-min-available} threshold</li>
 * </ol>
 * When any check fails, the original entities are returned, so that the upstream is never left
 * without available entities because of zone preference.
 *
 * @param <E> the type of entity to filter
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class ZonePreferenceFilter<E> {

    private static final Logger logger = LoggerFactory.getLogger(ZonePreferenceFilter.class);

    private final ZoneContext zoneContext;

    private final ZoneResolver<E> zoneResolver;

    public ZonePreferenceFilter(ZoneContext zoneContext, ZoneResolver<E> zoneResolver) {
        this.zoneContext = zoneContext;
        this.zoneResolver = zoneResolver;
    }

    public List<E> filter(final List<E> entities) {

        int totalSize = entities == null ? 0 : entities.size();

        // Empty case
        if (totalSize <= 1) {
            return entities;
        }

        // Disabled case
        if (!zoneContext.isEnabled()) {
            logger.trace("Zone Context feature is disabled! It could be enabled if the Spring property '{}' to be set 'true'",
                    ZONE_ENABLED_PROPERTY_NAME);
            return entities;
        }

        if (!zoneContext.isPreferenceEnabled()) {
            logger.trace("Zone Preference feature is disabled as default! It could be enabled if the Spring property '{}' to be set 'true'",
                    PREFERENCE_ENABLED_PROPERTY_NAME);
            return entities;
        }

        // Ignored case
        final String zone = zoneContext.getZone();
        if (isIgnored(zone)) {
            logger.trace("Zone Preference feature will be ignored, caused by zone : '{}'", zone);
            return entities;
        }

        List<E> targetEntities = entities;

        // Disable Zone Case
        String disabledZone = zoneContext.getPreferenceUpstreamDisabledZone();

        if (disabledZone != null) {
            targetEntities = filterDisabledZone(entities, disabledZone, totalSize);
            int currentSize = targetEntities.size();

            if (currentSize <= 1) { // Not enough entity available
                logger.trace("Not enough entity available after disabled zone['{}'] filter, "
                                + "the entities' total size : {} -> actual size : {}",
                        disabledZone, totalSize, currentSize);
                return entities;
            }
            // reassign total size
            totalSize = currentSize;
        }

        // Enabled case
        List<E> sameZoneEntities = new LinkedList<>();

        int zoneCount = 0;

        for (int i = 0; i < totalSize; i++) {
            E entity = targetEntities.get(i);

            String resolvedZone = resolveZone(entity);

            if (resolvedZone != null) {
                zoneCount++;
                if (matches(zone, resolvedZone)) {
                    sameZoneEntities.add(entity);
                }
            }
        }

        // Upstream entities ready case
        int upstreamReadyPercentage = zoneContext.getPreferenceUpstreamZoneReadyPercentage();
        if (isUpstreamZoneNotReady(zoneCount, totalSize, upstreamReadyPercentage)) {
            logger.trace("The ready percentage of entities with zone is under the threshold [{}%], total entities size : {} , "
                    + "ready entities size : {}", upstreamReadyPercentage, totalSize, zoneCount);
            return targetEntities;
        }

        // Zone preference matched case
        int sameZoneEntitiesSize = sameZoneEntities.size();
        if (sameZoneEntitiesSize > 0) {
            // The min available in the same zone threshold case
            int sameZoneMinAvailable = zoneContext.getPreferenceUpstreamSameZoneMinAvailable();
            if (isUnderSameZoneMinAvailableThreshold(sameZoneEntitiesSize, sameZoneMinAvailable)) {
                logger.trace("The size of same zone ['{}'] entities is under the threshold : {}, actual size : {}",
                        zone, sameZoneMinAvailable, sameZoneEntitiesSize);
                return targetEntities;
            }

            logger.trace("The same zone ['{}'] entities[size : {} , total : {}] are found!", zone, sameZoneEntitiesSize,
                    totalSize);
            return sameZoneEntities;
        }

        // No matched
        logger.trace("No same zone ['{}'] entity was found, total entities size : {} , zone count : {}", zone, totalSize,
                zoneCount);
        return targetEntities;
    }

    public int getOrder() {
        return zoneContext.getPreferenceFilterOrder();
    }

    private List<E> filterDisabledZone(List<E> entities, String disabledZone, int totalSize) {
        List<E> targetEntities = new LinkedList<>();

        String[] disabledZones = split(disabledZone, ',');

        for (int i = 0; i < totalSize; i++) {
            E entity = entities.get(i);

            String resolvedZone = resolveZone(entity);

            if (!isDisabledZone(resolvedZone, disabledZones)) {
                targetEntities.add(entity);
            }
        }

        logger.trace("After filtering the disabled zone['{}'] entities[size : {} -> {}] : {} -> {}", disabledZone, totalSize,
                targetEntities.size(), entities, targetEntities);
        return targetEntities;
    }

    private boolean isDisabledZone(String zone, String[] disabledZones) {
        for (String disabledZone : disabledZones) {
            if (Objects.equals(zone, disabledZone)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUpstreamZoneNotReady(int zoneCount, int entitiesSize, int zonePercentThreshold) {
        int percent = (zoneCount * 100 / entitiesSize);
        return percent < zonePercentThreshold;
    }

    private boolean isUnderSameZoneMinAvailableThreshold(int sameZoneEntitiesSize, int zonePreferenceThreshold) {
        return sameZoneEntitiesSize < zonePreferenceThreshold;
    }

    private String resolveZone(E entity) {
        return entity == null ? null : zoneResolver.resolve(entity);
    }

    private boolean matches(String zone, String resolvedZone) {
        return Objects.equals(zone, resolvedZone);
    }

    protected boolean isIgnored(String zone) {
        return isBlank(zone) || DEFAULT_ZONE.equalsIgnoreCase(zone);
    }
}
