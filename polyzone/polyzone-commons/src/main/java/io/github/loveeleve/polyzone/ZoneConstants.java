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

/**
 * The constants of zone.
 *
 * <p>
 * All properties are prefixed with {@value #ZONE_PROPERTY_NAME}, for example:
 * <pre>
 * polyzone.zone=eu-west-1
 * polyzone.zone.enabled=true
 * polyzone.zone.preference.enabled=true
 * polyzone.zone.preference.upstream.zone-ready-percentage=100
 * polyzone.zone.preference.upstream.same-zone-min-available=5
 * </pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public interface ZoneConstants {

    // Zone Properties

    /**
     * The property name suffix of enabled : ".enabled"
     */
    String ENABLED_PROPERTY_NAME_SUFFIX = ".enabled";

    /**
     * The default value of zone that is compatible with Eureka zone.
     */
    String DEFAULT_ZONE = "defaultZone";

    /**
     * The value of zone is used to revert the original zone where
     * {@link io.github.loveeleve.polyzone.spring.ZoneLocator} locates.
     */
    String ORIGINAL_ZONE = "originalZone";

    /**
     * The property name of zone : "polyzone.zone"
     */
    String ZONE_PROPERTY_NAME = "polyzone.zone";

    /**
     * The property name of current zone : "polyzone.current-zone"
     */
    String CURRENT_ZONE_PROPERTY_NAME = "polyzone.current-zone";

    /**
     * The property value of default zone that is enabled or not.
     */
    String DEFAULT_ZONE_ENABLED_PROPERTY_VALUE = "true";

    /**
     * The default value of zone that is enabled or not.
     */
    boolean DEFAULT_ZONE_ENABLED = Boolean.parseBoolean(DEFAULT_ZONE_ENABLED_PROPERTY_VALUE);

    /**
     * The property name of zone that is enabled or not : "polyzone.zone.enabled"
     */
    String ZONE_ENABLED_PROPERTY_NAME = ZONE_PROPERTY_NAME + ENABLED_PROPERTY_NAME_SUFFIX;

    // Zone Preference Properties

    /**
     * The property name prefix of zone preference : "polyzone.zone.preference."
     */
    String PREFERENCE_PROPERTY_NAME_PREFIX = ZONE_PROPERTY_NAME + ".preference.";

    /**
     * The property value of default value of zone preference that is enabled or not.
     */
    String DEFAULT_PREFERENCE_ENABLED_PROPERTY_VALUE = "false";

    /**
     * The default value of zone preference that is enabled or not.
     */
    boolean DEFAULT_ZONE_PREFERENCE_ENABLED = Boolean.parseBoolean(DEFAULT_PREFERENCE_ENABLED_PROPERTY_VALUE);

    /**
     * The property name of zone preference enabled or not : "polyzone.zone.preference.enabled"
     */
    String PREFERENCE_ENABLED_PROPERTY_NAME = PREFERENCE_PROPERTY_NAME_PREFIX + "enabled";

    // Zone Preference Filter Properties

    /**
     * The property name prefix of zone preference filter : "polyzone.zone.preference.filter."
     */
    String PREFERENCE_FILTER_PROPERTY_NAME_PREFIX = PREFERENCE_PROPERTY_NAME_PREFIX + "filter.";

    /**
     * The property value of default zone preference filter order : "10"
     */
    String DEFAULT_ZONE_PREFERENCE_FILTER_ORDER_PROPERTY_VALUE = "10";

    /**
     * The default value of zone preference filter order.
     */
    int DEFAULT_ZONE_PREFERENCE_FILTER_ORDER = Integer.parseInt(DEFAULT_ZONE_PREFERENCE_FILTER_ORDER_PROPERTY_VALUE);

    /**
     * The property name of zone preference filter order : "polyzone.zone.preference.filter.order"
     */
    String PREFERENCE_FILTER_ORDER_PROPERTY_NAME = PREFERENCE_FILTER_PROPERTY_NAME_PREFIX + "order";

    // Zone Preference Upstream Properties

    /**
     * The property name prefix of zone preference upstream : "polyzone.zone.preference.upstream."
     */
    String PREFERENCE_UPSTREAM_PROPERTY_NAME_PREFIX = PREFERENCE_PROPERTY_NAME_PREFIX + "upstream.";

    /**
     * The property value of default zone preference upstream zone ready percentage : "100"
     */
    String DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE_PROPERTY_VALUE = "100";

    /**
     * The default value of zone preference upstream zone ready percentage.
     */
    int DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE =
            Integer.parseInt(DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE_PROPERTY_VALUE);

    /**
     * The property name of zone preference upstream zone ready percentage :
     * "polyzone.zone.preference.upstream.zone-ready-percentage"
     */
    String PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE_PROPERTY_NAME =
            PREFERENCE_UPSTREAM_PROPERTY_NAME_PREFIX + "zone-ready-percentage";

    /**
     * The property value of default zone preference upstream same zone min available : "5"
     */
    String DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE_PROPERTY_VALUE = "5";

    /**
     * The default value of zone preference upstream same zone min available.
     */
    int DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE =
            Integer.parseInt(DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE_PROPERTY_VALUE);

    /**
     * The property name of zone preference upstream same zone min available :
     * "polyzone.zone.preference.upstream.same-zone-min-available"
     */
    String PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE_PROPERTY_NAME =
            PREFERENCE_UPSTREAM_PROPERTY_NAME_PREFIX + "same-zone-min-available";

    /**
     * The property value of default zone preference upstream disabled zone : null
     */
    String DEFAULT_PREFERENCE_UPSTREAM_DISABLED_ZONE = null;

    /**
     * The property name of zone preference upstream disabled zone :
     * "polyzone.zone.preference.upstream.disabled-zone"
     */
    String PREFERENCE_UPSTREAM_DISABLED_ZONE_PROPERTY_NAME =
            PREFERENCE_UPSTREAM_PROPERTY_NAME_PREFIX + "disabled-zone";

    // Zone Locator Properties

    /**
     * The property name prefix of zone locator : "polyzone.zone.locator."
     */
    String LOCATOR_PROPERTY_NAME_PREFIX = ZONE_PROPERTY_NAME + ".locator.";

    /**
     * The property value of default zone locator fast fail : "false"
     */
    String DEFAULT_LOCATOR_FAST_FAIL_PROPERTY_VALUE = "false";

    /**
     * The default value of zone locator fast fail.
     */
    boolean DEFAULT_LOCATOR_FAST_FAIL = Boolean.parseBoolean(DEFAULT_LOCATOR_FAST_FAIL_PROPERTY_VALUE);

    /**
     * The property name of zone locator fast fail : "polyzone.zone.locator.fast-fail"
     */
    String LOCATOR_FAST_FAIL_PROPERTY_NAME = LOCATOR_PROPERTY_NAME_PREFIX + "fast-fail";

    /**
     * The property value of default zone locator timeout : "3000"
     */
    String DEFAULT_TIMEOUT_PROPERTY_VALUE = "3000";

    /**
     * The default value of zone locator timeout.
     */
    int DEFAULT_TIMEOUT = Integer.parseInt(DEFAULT_TIMEOUT_PROPERTY_VALUE);

    /**
     * The property name of zone locator timeout : "polyzone.zone.locator.timeout"
     */
    String LOCATOR_TIMEOUT_PROPERTY_NAME = LOCATOR_PROPERTY_NAME_PREFIX + "timeout";

}
