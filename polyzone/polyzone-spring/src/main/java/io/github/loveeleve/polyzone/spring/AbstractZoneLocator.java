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
package io.github.loveeleve.polyzone.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.core.Ordered;

/**
 * The abstract implementation of {@link ZoneLocator}, providing the {@link Ordered order} and
 * {@link BeanNameAware bean name} support.
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class AbstractZoneLocator implements ZoneLocator, BeanNameAware, Ordered {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected String beanName;

    protected int order;

    public AbstractZoneLocator(int order) {
        setOrder(order);
    }

    @Override
    public final void setBeanName(String beanName) {
        this.beanName = beanName;
    }

    @Override
    public final int getOrder() {
        return order;
    }

    public final void setOrder(int order) {
        this.order = order;
    }

    @Override
    public String toString() {
        return "ZoneLocator{" + "beanName='" + beanName + '\'' + ", order=" + order + '}';
    }

}
