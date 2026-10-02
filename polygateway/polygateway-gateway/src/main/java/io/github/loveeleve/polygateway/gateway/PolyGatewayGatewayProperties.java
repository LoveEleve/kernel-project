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
package io.github.loveeleve.polygateway.gateway;

import io.github.loveeleve.polygateway.web.WebEndpointExclusion;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedList;
import java.util.List;

/**
 * The configuration properties of the PolyGateway gateway side, bound to the
 * {@code polygateway.gateway} prefix.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 *   polygateway:
 *     gateway:
 *       excludes:
 *         - patterns: "/internal/**"
 *           methods: POST
 * }</pre>
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = PolyGatewayGatewayProperties.PREFIX)
public class PolyGatewayGatewayProperties {

    /**
     * The prefix of the gateway side properties.
     */
    public static final String PREFIX = "polygateway.gateway";

    /**
     * Enables the endpoint level routing.
     */
    private boolean enabled = true;

    /**
     * The rules excluding the endpoints from the generated routes.
     */
    private List<WebEndpointExclusion> excludes = new LinkedList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<WebEndpointExclusion> getExcludes() {
        return excludes;
    }

    public void setExcludes(List<WebEndpointExclusion> excludes) {
        this.excludes = excludes;
    }
}
