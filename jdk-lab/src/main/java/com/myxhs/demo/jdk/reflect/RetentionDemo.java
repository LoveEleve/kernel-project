package com.myxhs.demo.jdk.reflect;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;

/**
 * 注解保留策略：SOURCE / CLASS / RUNTIME 在 class 文件与运行时的可见性。
 *
 * 运行后配合 javap 观察：
 *   javap -v -p -cp target/classes com.myxhs.demo.jdk.reflect.RetentionDemo | grep -A6 "void hello"
 *
 * 观察点：
 * 1) SOURCE 的注解在 class 文件里完全没有
 * 2) CLASS（含"不写 @Retention"的默认值）写在 RuntimeInvisibleAnnotations 里，运行时读不到
 * 3) RUNTIME 写在 RuntimeVisibleAnnotations 里，反射能读到 —— 框架注解的前提
 */
public class RetentionDemo {

    @Retention(RetentionPolicy.SOURCE)
    @interface SourceAnno {
    }

    @Retention(RetentionPolicy.CLASS)
    @interface ClassAnno {
    }

    @interface DefaultAnno {   // 不写 @Retention —— 默认 CLASS
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface RuntimeAnno {
    }

    @SourceAnno
    @ClassAnno
    @DefaultAnno
    @RuntimeAnno
    public void hello() {
    }

    public static void main(String[] args) throws Exception {
        Method m = RetentionDemo.class.getMethod("hello");
        System.out.println("运行时读到的注解: " + java.util.Arrays.toString(m.getAnnotations()));
    }
}
