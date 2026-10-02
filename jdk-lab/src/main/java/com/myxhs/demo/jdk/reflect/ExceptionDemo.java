package com.myxhs.demo.jdk.reflect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 反射的四类异常，一次见全。
 *
 * 观察点：
 * 1) 反射自己失败：ClassNotFoundException（找不到类）、NoSuchMethodException（找不到成员）、IllegalAccessException（没权限）
 * 2) 目标方法失败：一律包成 InvocationTargetException —— 受检、非受检都被包，必须 getCause() 拆包
 * 3) PrivateTarget 必须是"另一个顶层类"：嵌套类在 JDK 11+ 属于同一 nest，可以互访私有成员，复现不出 IllegalAccessException
 */
public class ExceptionDemo {

    static class User {
        public void boomChecked() throws java.io.IOException {
            throw new java.io.IOException("我是一个受检异常");
        }

        public void boomUnchecked() {
            throw new IllegalArgumentException("我是一个非受检异常");
        }
    }

    public static void main(String[] args) {
        // 1. ClassNotFoundException
        try {
            Class.forName("com.not.exist.User");
        } catch (ClassNotFoundException e) {
            System.out.println("1) " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 2. NoSuchMethodException
        try {
            User.class.getMethod("noSuchMethod");
        } catch (Exception e) {
            System.out.println("2) " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 3. IllegalAccessException（另一个顶层类的私有方法，不是 nestmate）
        try {
            Method secret = PrivateTarget.class.getDeclaredMethod("secret");
            secret.invoke(new PrivateTarget());
        } catch (Exception e) {
            System.out.println("3) " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 4. InvocationTargetException（受检 / 非受检各来一次）
        invokeAndShow("boomChecked");
        invokeAndShow("boomUnchecked");
    }

    static void invokeAndShow(String name) {
        try {
            Method m = User.class.getMethod(name);
            m.invoke(new User());
        } catch (InvocationTargetException e) {
            System.out.println("4) 捕获到: InvocationTargetException");
            System.out.println("   getCause() 才是真正的异常: " + e.getCause().getClass().getSimpleName()
                    + ": " + e.getCause().getMessage());
        } catch (Exception e) {
            System.out.println("4) 捕获到: " + e.getClass().getSimpleName());
        }
    }
}

class PrivateTarget {
    private void secret() {
        System.out.println("    私有方法被执行了");
    }
}
