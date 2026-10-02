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

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Consumer;

import static io.github.loveeleve.polyzone.Strings.isBlank;
import static io.github.loveeleve.polyzone.Strings.isNotBlank;
import static io.github.loveeleve.polyzone.Strings.split;
import static io.github.loveeleve.polyzone.Strings.trimAllWhitespace;
import static io.github.loveeleve.polyzone.ZoneConstants.CURRENT_ZONE_PROPERTY_NAME;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_DISABLED_ZONE;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE_ENABLED;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE_PREFERENCE_ENABLED;
import static io.github.loveeleve.polyzone.ZoneConstants.DEFAULT_ZONE_PREFERENCE_FILTER_ORDER;

/**
 * The context of zone, it holds the current zone and all zone-preference settings.
 *
 * <p>
 * Every mutating method fires a {@link java.beans.PropertyChangeEvent} when the value actually
 * changes, so that downstream components (load balancer, data source, cache, ...) can react to a
 * zone switch.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public class ZoneContext {

    private static final Logger logger = LoggerFactory.getLogger(ZoneContext.class);

    private static final ZoneContext instance = new ZoneContext();

    private volatile boolean enabled = DEFAULT_ZONE_ENABLED;

    private volatile String zone = DEFAULT_ZONE;

    private volatile boolean preferenceEnabled = DEFAULT_ZONE_PREFERENCE_ENABLED;

    private volatile int preferenceFilterOrder = DEFAULT_ZONE_PREFERENCE_FILTER_ORDER;

    private volatile int preferenceUpstreamZoneReadyPercentage = DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE;

    private volatile int preferenceUpstreamSameZoneMinAvailable = DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE;

    private volatile String preferenceUpstreamDisabledZone = DEFAULT_PREFERENCE_UPSTREAM_DISABLED_ZONE;

    private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);

    public void setEnabled(boolean enabled) {
        updateProperty("enabled", this.enabled, enabled, value -> this.enabled = value);
    }

    public void setZone(String zone) {
        updateProperty("zone", this.zone, trimAllWhitespace(zone), value -> {
            this.zone = value;
            // Keep the global accessor consistent with the context
            if (isNotBlank(value)) {
                System.setProperty(CURRENT_ZONE_PROPERTY_NAME, value);
            } else {
                System.clearProperty(CURRENT_ZONE_PROPERTY_NAME);
            }
        });
    }

    public void setPreferenceEnabled(boolean preferenceEnabled) {
        updateProperty("preferenceEnabled", this.preferenceEnabled, preferenceEnabled,
                value -> this.preferenceEnabled = value);
    }

    public void setPreferenceFilterOrder(int preferenceFilterOrder) {
        updateProperty("preferenceFilterOrder", this.preferenceFilterOrder, preferenceFilterOrder,
                value -> this.preferenceFilterOrder = value);
    }

    public void setPreferenceUpstreamZoneReadyPercentage(int preferenceUpstreamZoneReadyPercentage) {
        updateProperty("preferenceUpstreamZoneReadyPercentage", this.preferenceUpstreamZoneReadyPercentage,
                preferenceUpstreamZoneReadyPercentage, value -> this.preferenceUpstreamZoneReadyPercentage = value);
    }

    public void setPreferenceUpstreamSameZoneMinAvailable(int preferenceUpstreamSameZoneMinAvailable) {
        updateProperty("preferenceUpstreamSameZoneMinAvailable", this.preferenceUpstreamSameZoneMinAvailable,
                preferenceUpstreamSameZoneMinAvailable, value -> this.preferenceUpstreamSameZoneMinAvailable = value);
    }

    public void setPreferenceUpstreamDisabledZone(String preferenceUpstreamDisabledZone) {
        updateProperty("preferenceUpstreamDisabledZone", this.preferenceUpstreamDisabledZone,
                resolveCommaDelimited(preferenceUpstreamDisabledZone),
                value -> this.preferenceUpstreamDisabledZone = value);
    }

    private String resolveCommaDelimited(String value) {
        if (isBlank(value)) {
            return value;
        }

        String[] values = split(value, ',');

        StringJoiner stringJoiner = new StringJoiner(",");

        for (String element : values) {
            stringJoiner.add(trimAllWhitespace(element));
        }

        return stringJoiner.toString();
    }

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        propertyChangeSupport.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        propertyChangeSupport.removePropertyChangeListener(listener);
    }

    /**
     * @return Zone context enabled or not
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return current zone in the context
     */
    public String getZone() {
        return zone;
    }

    /**
     * @return Zone preference enabled or not
     */
    public boolean isPreferenceEnabled() {
        return preferenceEnabled;
    }

    /**
     * @return the order of zone preference filter
     */
    public int getPreferenceFilterOrder() {
        return preferenceFilterOrder;
    }

    /**
     * @return the zone ready percentage of upstream servers(nodes) for zone-preference
     */
    public int getPreferenceUpstreamZoneReadyPercentage() {
        return preferenceUpstreamZoneReadyPercentage;
    }

    /**
     * @return the min available of upstream servers(nodes) in the same zone for zone-preference
     */
    public int getPreferenceUpstreamSameZoneMinAvailable() {
        return preferenceUpstreamSameZoneMinAvailable;
    }

    /**
     * @return the disabled zone of upstream servers(nodes) for zone-preference
     */
    public String getPreferenceUpstreamDisabledZone() {
        return preferenceUpstreamDisabledZone;
    }

    /**
     * Enable and return the previous enabled status
     *
     * @return previous enabled status
     */
    public boolean enable() {
        boolean enabled = this.enabled;
        if (!enabled) {
            setEnabled(true);
        }
        return enabled;
    }

    /**
     * Reset to the default status
     */
    public void reset() {
        setEnabled(DEFAULT_ZONE_ENABLED);
        setZone(DEFAULT_ZONE);
        setPreferenceEnabled(DEFAULT_ZONE_PREFERENCE_ENABLED);
        setPreferenceFilterOrder(DEFAULT_ZONE_PREFERENCE_FILTER_ORDER);
        setPreferenceUpstreamZoneReadyPercentage(DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE);
        setPreferenceUpstreamSameZoneMinAvailable(DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE);
        setPreferenceUpstreamDisabledZone(DEFAULT_PREFERENCE_UPSTREAM_DISABLED_ZONE);
    }

    /**
     * Update the property value and fire a {@link java.beans.PropertyChangeEvent} after the new
     * value has been committed, so that listeners always observe the up-to-date value.
     */
    private <T> void updateProperty(String propertyName, T previousValue, T newValue, Consumer<T> valueSetter) {
        if (Objects.equals(previousValue, newValue)) {
            logger.trace("The property value [name : '{}'] is not changed : '{}'", propertyName, previousValue);
            return;
        }
        valueSetter.accept(newValue);
        propertyChangeSupport.firePropertyChange(propertyName, previousValue, newValue);
        logger.info("The property value [name : '{}'] is changed from '{}' to '{}'", propertyName, previousValue,
                newValue);
    }

    @Override
    public String toString() {
        return "ZoneContext{"
                + "enabled=" + enabled
                + ", zone='" + zone + '\''
                + ", preferenceEnabled=" + preferenceEnabled
                + ", preferenceFilterOrder=" + preferenceFilterOrder
                + ", preferenceUpstreamZoneReadyPercentage=" + preferenceUpstreamZoneReadyPercentage
                + ", preferenceUpstreamSameZoneMinAvailable=" + preferenceUpstreamSameZoneMinAvailable
                + ", preferenceUpstreamDisabledZone=" + preferenceUpstreamDisabledZone + '}';
    }

    /**
     * Get the current {@link ZoneContext}
     *
     * @return non-null
     */
    public static ZoneContext get() {
        return instance;
    }

    /**
     * Get the current zone
     *
     * @return non-null
     */
    public static String getCurrentZone() {
        return System.getProperty(CURRENT_ZONE_PROPERTY_NAME, DEFAULT_ZONE);
    }
}
