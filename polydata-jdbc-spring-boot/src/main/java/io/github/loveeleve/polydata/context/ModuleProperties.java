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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Dynamic JDBC Module {@link ConfigurationProperties}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class ModuleProperties {

    private Dynamic dynamic = new Dynamic();

    private final AutoConfiguration autoConfiguration = new AutoConfiguration();

    private Set<String> bannedModules = new LinkedHashSet<>();

    public Dynamic getDynamic() {
        return dynamic;
    }

    public void setDynamic(Dynamic dynamic) {
        this.dynamic = dynamic;
    }

    public Set<String> getBannedModules() {
        return bannedModules;
    }

    public void setBannedModules(Set<String> bannedModules) {
        this.bannedModules = bannedModules;
    }

    public static class Dynamic {

        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class AutoConfiguration {

        private Set<String> basePackages = new LinkedHashSet<>();

        public Set<String> getBasePackages() {
            return basePackages;
        }

        public void setBasePackages(Set<String> basePackages) {
            this.basePackages = basePackages;
        }
    }

}
