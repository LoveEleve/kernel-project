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
package io.github.loveeleve.polyconfig.spring.event;

import java.util.Objects;

/**
 * A single configuration property change: the property name and its old / new values.
 *
 * <p>
 * The old value may be {@code null} when the previous value is unknown (e.g. the change was
 * detected from a Spring Cloud {@code EnvironmentChangeEvent}, which only carries the changed
 * property names).
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public final class ConfigPropertyChangedEvent {

    private final String propertyName;

    private final Object oldValue;

    private final Object newValue;

    public ConfigPropertyChangedEvent(String propertyName, Object oldValue, Object newValue) {
        this.propertyName = propertyName;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public Object getOldValue() {
        return oldValue;
    }

    public Object getNewValue() {
        return newValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConfigPropertyChangedEvent)) {
            return false;
        }
        ConfigPropertyChangedEvent that = (ConfigPropertyChangedEvent) o;
        return Objects.equals(propertyName, that.propertyName)
                && Objects.equals(oldValue, that.oldValue)
                && Objects.equals(newValue, that.newValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyName, oldValue, newValue);
    }

    @Override
    public String toString() {
        return "ConfigPropertyChangedEvent{"
                + "propertyName='" + propertyName + '\''
                + ", oldValue=" + oldValue
                + ", newValue=" + newValue
                + '}';
    }
}
