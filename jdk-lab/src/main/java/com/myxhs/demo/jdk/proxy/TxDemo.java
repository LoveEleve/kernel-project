package com.myxhs.demo.jdk.proxy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 迷你 Spring 事务：注解 + 动态代理 + 反射调用。
 *
 * 观察点：
 * 1) 注解只是数据；事务的 BEGIN/COMMIT/ROLLBACK 全部由代理在业务方法外面加上
 * 2) JDK 动态代理传进来的 method 是"接口的方法"，注解贴在实现类上时读不到 —— 需要解析 most specific method
 * 3) 目标抛出的异常被包成 InvocationTargetException，必须拆 getCause 再向外抛
 */
public class TxDemo {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface MyTransactional {
    }

    interface UserService {
        void transfer();

        void query();
    }

    static class UserServiceImpl implements UserService {
        static int count = 0;

        @MyTransactional
        public void transfer() {
            count++;
            System.out.println("   [业务] 执行转账：扣款 + 入账");
            if (count == 2) {
                throw new RuntimeException("模拟转账失败");
            }
        }

        public void query() {
            System.out.println("   [业务] 查询余额");
        }
    }

    static UserService wrap(UserService target) {
        return (UserService) Proxy.newProxyInstance(
                target.getClass().getClassLoader(),
                target.getClass().getInterfaces(),
                (proxy, method, args) -> {
                    Method targetMethod = target.getClass().getMethod(method.getName(), method.getParameterTypes());
                    System.out.println("   代理看到的方法: " + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                            + "，有注解? " + method.isAnnotationPresent(MyTransactional.class));
                    System.out.println("   解析到实现方法: " + targetMethod.getDeclaringClass().getSimpleName() + "." + targetMethod.getName()
                            + "，有注解? " + targetMethod.isAnnotationPresent(MyTransactional.class));

                    if (!targetMethod.isAnnotationPresent(MyTransactional.class)) {
                        try {
                            return method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }

                    System.out.println("   [事务] BEGIN");
                    try {
                        Object result = method.invoke(target, args);
                        System.out.println("   [事务] COMMIT");
                        return result;
                    } catch (InvocationTargetException e) {
                        System.out.println("   [事务] ROLLBACK，原因: " + e.getCause().getMessage());
                        throw e.getCause();
                    }
                });
    }

    public static void main(String[] args) {
        UserService service = wrap(new UserServiceImpl());
        System.out.println("容器返回的对象的类: " + service.getClass().getName());

        for (int i = 1; i <= 3; i++) {
            System.out.println("\n-- 第 " + i + " 次 transfer --");
            try {
                service.transfer();
            } catch (RuntimeException e) {
                System.out.println("调用方收到异常: " + e.getMessage());
            }
        }

        System.out.println("\n-- query --");
        service.query();
    }
}
