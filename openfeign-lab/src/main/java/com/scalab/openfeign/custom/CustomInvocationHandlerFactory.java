package com.scalab.openfeign.custom;

import feign.InvocationHandlerFactory;
import feign.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class CustomInvocationHandlerFactory implements InvocationHandlerFactory {

    private final InvocationHandlerFactory delegate;

    public CustomInvocationHandlerFactory(InvocationHandlerFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
        InvocationHandler delegateHandler = delegate.create(target, dispatch);
        return new CustomInvocationHandler(target, delegateHandler);
    }

    static class CustomInvocationHandler implements InvocationHandler {

        private static final Logger log = LoggerFactory.getLogger(CustomInvocationHandler.class);

        private final Target<?> target;
        private final InvocationHandler delegate;
        private final ConcurrentHashMap<String, InvocationStats> methodStats = new ConcurrentHashMap<>();

        CustomInvocationHandler(Target<?> target, InvocationHandler delegate) {
            this.target = target;
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return delegate.invoke(proxy, method, args);
            }

            String methodKey = target.type().getSimpleName() + "#" + method.getName();
            InvocationStats stats = methodStats.computeIfAbsent(methodKey, k -> new InvocationStats());

            long start = System.nanoTime();
            try {
                Object result = delegate.invoke(proxy, method, args);
                long cost = System.nanoTime() - start;
                stats.record(cost);
                log.debug("[CustomInvocationHandler] {} -> OK, cost={}ns, totalCalls={}, avgCost={}ns",
                        methodKey, cost, stats.count(), stats.average());
                return result;
            } catch (Throwable t) {
                long cost = System.nanoTime() - start;
                stats.record(cost);
                log.warn("[CustomInvocationHandler] {} -> ERROR({}), cost={}ns, totalCalls={}",
                        methodKey, t.getClass().getSimpleName(), cost, stats.count());
                throw t;
            }
        }

        private static class InvocationStats {
            private final LongAdder count = new LongAdder();
            private final LongAdder totalTime = new LongAdder();

            void record(long cost) {
                count.increment();
                totalTime.add(cost);
            }

            long count() { return count.sum(); }

            long average() {
                long c = count();
                return c > 0 ? totalTime.sum() / c : 0;
            }
        }
    }
}
