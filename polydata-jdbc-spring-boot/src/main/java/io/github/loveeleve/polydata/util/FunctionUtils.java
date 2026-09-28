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
package io.github.loveeleve.polydata.util;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Utilities class for Java Function
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
public abstract class FunctionUtils {

    private FunctionUtils() {}

    public static <T> boolean setIfAbsent(Supplier<T> supplier, Supplier<T> newSupplier, Consumer<T> consumer) {
        return setValueIfAbsent(supplier.get(), newSupplier, consumer);
    }

    public static <T> boolean setValueIfAbsent(T oldValue, Supplier<T> newSupplier, Consumer<T> consumer) {
        if (oldValue == null) {
            consumer.accept(newSupplier.get());
            return true;
        }
        return false;
    }

}
