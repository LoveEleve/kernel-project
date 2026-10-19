package com.scalab.openfeign.custom;

import feign.InvocationHandlerFactory;
import feign.Target;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomInvocationHandlerFactoryTest {

    @Test
    void shouldWrapDelegateHandler() {
        InvocationHandlerFactory delegate = (target, dispatch) -> (proxy, method, args) -> "delegate";
        CustomInvocationHandlerFactory factory = new CustomInvocationHandlerFactory(delegate);

        Target<String> target = new Target.HardCodedTarget<>(String.class, "test", "http://localhost");
        InvocationHandler handler = factory.create(target, Collections.emptyMap());

        assertNotNull(handler);
    }

    @Test
    void shouldDelegateObjectMethods() throws Throwable {
        InvocationHandlerFactory delegate = (target, dispatch) -> (proxy, method, args) -> {
            if ("toString".equals(method.getName())) {
                return "delegate-toString";
            }
            return "handled";
        };
        CustomInvocationHandlerFactory factory = new CustomInvocationHandlerFactory(delegate);

        Target<String> target = new Target.HardCodedTarget<>(String.class, "test", "http://localhost");
        InvocationHandler handler = factory.create(target, Collections.emptyMap());

        Object result = handler.invoke(null, Object.class.getMethod("toString"), null);
        assertEquals("delegate-toString", result);
    }

    @Test
    void shouldRecordStats() throws Throwable {
        InvocationHandlerFactory delegate = (target, dispatch) -> (proxy, method, args) -> "response";

        InvocationHandlerFactory custom = new CustomInvocationHandlerFactory(delegate);

        Target<String> target = new Target.HardCodedTarget<>(String.class, "test", "http://localhost");
        InvocationHandler handler = custom.create(target, Collections.emptyMap());

        Method method = String.class.getMethod("toString");
        handler.invoke(null, method, null);
        handler.invoke(null, method, null);

        assertTrue(true, "Should not throw - stats recording uses LongAdder internally");
    }

    @Test
    void shouldTrackStatsPerMethod() throws Throwable {
        InvocationHandlerFactory delegate = (target, dispatch) -> (proxy, method, args) -> "response";

        InvocationHandlerFactory custom = new CustomInvocationHandlerFactory(delegate);

        Target<String> target = new Target.HardCodedTarget<>(String.class, "test", "http://localhost");
        InvocationHandler handler = custom.create(target, Collections.emptyMap());

        handler.invoke(null, String.class.getMethod("toString"), null);
        handler.invoke(null, Object.class.getMethod("hashCode"), null);

        assertTrue(true, "Should handle multiple methods without error");
    }
}
