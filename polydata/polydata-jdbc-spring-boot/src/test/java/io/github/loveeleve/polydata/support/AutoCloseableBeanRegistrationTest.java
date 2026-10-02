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
package io.github.loveeleve.polydata.support;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.loveeleve.polydata.support.BeanRegistrar.registerBean;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies whether the instance registered by {@link BeanRegistrar#registerBean} is closed when its
 * context is closed (Spring infers the {@code close()} destroy method for {@link AutoCloseable}
 * beans).
 *
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
class AutoCloseableBeanRegistrationTest {

    @Test
    void testRegisteredAutoCloseableBeanIsClosed() {
        AtomicBoolean closed = new AtomicBoolean(false);
        GenericApplicationContext context = new GenericApplicationContext();
        registerBean(context, "closeable", (AutoCloseable) () -> closed.set(true), false);

        context.refresh();
        context.close();

        assertTrue(closed.get(), "The AutoCloseable bean must be closed when the context is closed");
    }
}
