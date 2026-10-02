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
package io.github.loveeleve.polygateway.spring.boot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The configuration properties of the PolyGateway provider side, bound to the
 * {@code polygateway.provider} prefix.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   polygateway:
 *     provider:
 *       enabled: true
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = PolyGatewayProviderProperties.PREFIX)
public class PolyGatewayProviderProperties {

    /**
     * The prefix of the provider side properties.
     */
    public static final String PREFIX = "polygateway.provider";

    /**
     * Enables the collection and the publication of the web endpoints.
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
