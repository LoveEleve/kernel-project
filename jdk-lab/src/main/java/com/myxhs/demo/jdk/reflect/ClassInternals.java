package com.myxhs.demo.jdk.reflect;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.ref.SoftReference;

/**
 * 翻开 Class 对象：成员信息是懒生成 + 缓存的（ReflectionData，软引用）。
 *
 * 运行需要打开 JDK 内部包（读的是 java.lang.Class 的私有字段）：
 *   java --add-opens java.base/java.lang=ALL-UNNAMED -cp target/classes com.myxhs.demo.jdk.reflect.ClassInternals
 *
 * 观察点：
 * 1) 刚拿到 Class 对象时 reflectionData = null —— 成员"名片"一张都还没造
 * 2) 调用 getDeclaredMethods() 之后，缓存出现且方法数组已填充
 * 3) classLoader 字段被 JDK 从反射可见列表中过滤掉了（档案窗口是有选择地开的）
 */
public class ClassInternals {

    static class User {
        private Long id;
        private String name;

        public void hello() {
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> c = User.class;
        show(c, "① 刚拿到 Class 对象（还没查找过任何成员）");
        c.getDeclaredMethods();   // 触发一次"找方法"
        show(c, "② 调用 getDeclaredMethods() 之后");
    }

    static void show(Class<?> c, String title) throws Exception {
        System.out.println("== " + title + " ==");
        Field rdField = Class.class.getDeclaredField("reflectionData");
        rdField.setAccessible(true);
        SoftReference<?> ref = (SoftReference<?>) rdField.get(c);
        Object rd = (ref == null) ? null : ref.get();
        System.out.println("reflectionData: " + (rd == null ? "null" : "已创建（软引用）"));
        if (rd != null) {
            Field dm = rd.getClass().getDeclaredField("declaredMethods");
            dm.setAccessible(true);
            Object arr = dm.get(rd);
            System.out.println("  declaredMethods 缓存: " + (arr == null ? "null（还没填充）" : "长度 " + Array.getLength(arr)));
        }
        for (String fn : new String[]{"name", "classLoader", "module"}) {
            try {
                Field f = Class.class.getDeclaredField(fn);
                f.setAccessible(true);
                System.out.println("  " + fn + " = " + f.get(c));
            } catch (NoSuchFieldException e) {
                System.out.println("  " + fn + " = （反射不可见：JDK 把它从字段列表里过滤掉了）");
            }
        }
        System.out.println();
    }
}
